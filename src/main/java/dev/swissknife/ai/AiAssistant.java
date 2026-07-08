package dev.swissknife.ai;

import dev.swissknife.util.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Assistência opcional por IA com consentimento, redação, cache e limites. */
public final class AiAssistant {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public Result execute(Path configuration,String task,Path input,boolean send,boolean consent)
        throws IOException,InterruptedException{
        return execute(configuration,task,input,send,consent,"");
    }
    public Result execute(Path configuration,String task,Path input,boolean send,boolean consent,String question)
        throws IOException,InterruptedException{
        Properties p=new Properties();try(var reader=Files.newBufferedReader(configuration)){p.load(reader);}
        String provider=p.getProperty("provider","openai-compatible").toLowerCase(Locale.ROOT);
        String model=required(p,"model"),raw=Files.readString(input);
        String redacted=redact(raw);int maxInput=integer(p,"maxInputCharacters",100_000);
        if(redacted.length()>maxInput)redacted=redacted.substring(0,maxInput)+"\n[TRUNCADO]";
        String prompt=prompt(task,redacted,p.getProperty("additionalInstructions",""),question);
        int estimatedTokens=Math.max(1,prompt.length()/4);
        int maxTokens=integer(p,"maxTokens",2_000);
        double estimatedCost=estimatedTokens/1000.0*decimal(p,"inputCostPer1k",0);
        Request request=request(provider,p,model,prompt,maxTokens);
        if(!send)return new Result(provider,model,task,true,false,true,estimatedTokens,maxTokens,estimatedCost,
            request.url(),redactHeaders(request.headers()),redactRequest(request.body()),"","",false);
        if(!consent)throw new IllegalArgumentException("Envio para IA exige --consent após revisar a prévia redigida");
        double maxCost=decimal(p,"maxEstimatedCost",1.0);
        if(estimatedCost>maxCost)throw new IllegalArgumentException("Custo estimado "+estimatedCost+" excede "+maxCost);
        Path cache=Path.of(p.getProperty("cacheDirectory",".swissknife/ai-cache")).toAbsolutePath().normalize();
        String key=hash(provider+model+task+prompt);Path cached=cache.resolve(key+".json");
        if(Boolean.parseBoolean(p.getProperty("cache","true"))&&Files.isRegularFile(cached)){
            Map<String,Object> saved=Json.object(Files.readString(cached));
            return new Result(provider,model,task,false,true,true,estimatedTokens,maxTokens,estimatedCost,
                request.url(),Map.of(),"",String.valueOf(saved.get("content")),String.valueOf(saved.get("raw")),true);
        }
        String token=token(p,provider);Map<String,String> headers=new LinkedHashMap<>(request.headers());
        applyAuthentication(provider,headers,token,p);
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(request.url()))
            .timeout(Duration.ofSeconds(integer(p,"timeoutSeconds",120)));
        headers.forEach(builder::header);
        HttpResponse<String> response=client.send(builder.POST(HttpRequest.BodyPublishers.ofString(request.body())).build(),
            HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()<200||response.statusCode()>=300)
            throw new IOException("Provedor IA respondeu HTTP "+response.statusCode()+": "+truncate(response.body(),1000));
        String content=extract(provider,response.body());
        if(Boolean.parseBoolean(p.getProperty("cache","true"))){
            Files.createDirectories(cache);Files.writeString(cached,Json.stringify(Map.of("content",content,
                "raw",response.body(),"createdAt",Instant.now().toString())),StandardCharsets.UTF_8);
        }
        audit(p,provider,model,task,input,estimatedTokens);
        return new Result(provider,model,task,false,true,true,estimatedTokens,maxTokens,estimatedCost,
            request.url(),redactHeaders(headers),"",content,response.body(),false);
    }

    private Request request(String provider,Properties p,String model,String prompt,int maxTokens){
        String url=p.getProperty("url",switch(provider){
            case"anthropic"->"https://api.anthropic.com/v1/messages";
            case"ollama","local"->"http://127.0.0.1:11434/api/chat";
            default->"https://api.openai.com/v1/chat/completions";});
        Object body=switch(provider){
            case"anthropic"->Map.of("model",model,"max_tokens",maxTokens,"messages",List.of(Map.of("role","user","content",prompt)));
            case"ollama","local"->Map.of("model",model,"stream",false,"messages",List.of(Map.of("role","user","content",prompt)));
            default->Map.of("model",model,"max_tokens",maxTokens,"temperature",decimal(p,"temperature",0.2),
                "messages",List.of(Map.of("role","system","content","Você é um revisor técnico Java. Não invente fatos ausentes."),
                    Map.of("role","user","content",prompt)));
        };
        return new Request(url,Map.of("Content-Type","application/json"),Json.stringify(body));
    }
    private String prompt(String task,String input,String additional,String question){
        String instruction=switch(task.toLowerCase(Locale.ROOT)){
            case"explain"->"Explique os findings em PT-BR, com causa, impacto, evidência e correção.";
            case"summarize"->"Produza resumo executivo e resumo técnico, preservando números e incertezas.";
            case"prioritize"->"Priorize por risco, exposição, impacto e esforço. Justifique a ordem.";
            case"fix"->"Sugira correções mínimas e seguras. Não afirme que foram aplicadas.";
            case"sql"->"Explique riscos da query/plano SQL e proponha alternativas mensuráveis.";
            case"anonymize-policy"->"Proponha política de anonimização LGPD revisável, preservando relacionamentos.";
            case"adr"->"Gere uma ADR com contexto, decisão, alternativas, consequências e rollback.";
            case"modernization"->"Crie plano incremental de modernização Java com testes e rollback.";
            case"group-errors"->"Agrupe semanticamente os erros/exceções por causa raiz provável, ignorando diferenças superficiais de mensagem.";
            case"query"->{
                if(question.isBlank())throw new IllegalArgumentException("Tarefa 'query' exige uma pergunta (parâmetro question)");
                yield "Responda em PT-BR, somente com base na entrada abaixo; se a resposta não estiver na entrada, diga que não sabe. Pergunta: "+question;
            }
            default->throw new IllegalArgumentException("Tarefa IA: explain, summarize, prioritize, fix, sql, anonymize-policy, adr, modernization, group-errors ou query");
        };
        return instruction+(additional.isBlank()?"":"\nInstruções adicionais: "+additional)+"\n\nEntrada redigida:\n"+input;
    }
    private String redact(String value){
        String result=value.replaceAll("(?i)(password|passwd|secret|token|api[_-]?key)(\\s*[:=]\\s*)[^\\s,\"'}]+","$1$2***");
        result=result.replaceAll("\\bAKIA[0-9A-Z]{16}\\b","***AWS_KEY***");
        result=result.replaceAll("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----","***PRIVATE_KEY***");
        result=result.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*","Bearer ***");
        return result;
    }
    private String token(Properties p,String provider){
        if(Set.of("ollama","local").contains(provider))return"";
        String env=p.getProperty("apiKeyEnv");
        if(env==null||env.isBlank())throw new IllegalArgumentException("apiKeyEnv é obrigatório para "+provider);
        String token=System.getenv(env);if(token==null||token.isBlank())throw new IllegalArgumentException("Variável ausente: "+env);
        return token;
    }
    private void applyAuthentication(String provider,Map<String,String> headers,String token,Properties p){
        if(provider.equals("anthropic")){headers.put("x-api-key",token);headers.put("anthropic-version",p.getProperty("apiVersion","2023-06-01"));}
        else if(!token.isBlank())headers.put("Authorization","Bearer "+token);
    }
    private String extract(String provider,String raw){
        try{Map<String,Object> root=Json.object(raw);
            if(provider.equals("anthropic")){List<?> content=(List<?>)root.get("content");return String.valueOf(((Map<?,?>)content.getFirst()).get("text"));}
            if(Set.of("ollama","local").contains(provider))return String.valueOf(((Map<?,?>)root.get("message")).get("content"));
            List<?> choices=(List<?>)root.get("choices");return String.valueOf(((Map<?,?>)((Map<?,?>)choices.getFirst()).get("message")).get("content"));
        }catch(Exception e){throw new IllegalArgumentException("Resposta IA inesperada: "+e.getMessage());}
    }
    private void audit(Properties p,String provider,String model,String task,Path input,int tokens)throws IOException{
        Path file=Path.of(p.getProperty("auditFile",".swissknife/ai-audit.jsonl")).toAbsolutePath().normalize();
        Path parent=file.getParent();if(parent!=null)Files.createDirectories(parent);
        Files.writeString(file,Json.stringify(Map.of("timestamp",Instant.now().toString(),"provider",provider,
            "model",model,"task",task,"input",input.toString(),"estimatedInputTokens",tokens))+"\n",
            StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private Map<String,String> redactHeaders(Map<String,String> headers){Map<String,String> copy=new LinkedHashMap<>(headers);
        copy.replaceAll((k,v)->k.toLowerCase(Locale.ROOT).matches(".*(authorization|key|token).*")?"***":v);return copy;}
    private String redactRequest(String body){return truncate(body.replaceAll("(?i)(Bearer\\\\?\"?\\s+)[^\"\\s]+","$1***"),4000);}
    private String required(Properties p,String key){String v=p.getProperty(key);if(v==null||v.isBlank())throw new IllegalArgumentException(key+" é obrigatório");return v;}
    private int integer(Properties p,String key,int fallback){try{return Integer.parseInt(p.getProperty(key,String.valueOf(fallback)));}catch(Exception e){return fallback;}}
    private double decimal(Properties p,String key,double fallback){try{return Double.parseDouble(p.getProperty(key,String.valueOf(fallback)));}catch(Exception e){return fallback;}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private String truncate(String value,int max){return value.length()>max?value.substring(0,max)+"…":value;}
    private record Request(String url,Map<String,String> headers,String body){}
    public record Result(String provider,String model,String task,boolean dryRun,boolean sent,boolean redacted,
        int estimatedInputTokens,int maxOutputTokens,double estimatedCost,String url,Map<String,String> headers,
        String requestPreview,String content,String rawResponse,boolean cached){}
}
