package dev.swissknife.governance;

import dev.swissknife.cli.CliConfig;
import dev.swissknife.debt.TechnicalDebtTracker;
import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Auditoria de testes/configuração e quality gate consolidado para releases. */
public final class DevelopmentGovernanceAnalyzer {
    public TestReport tests(Path root) throws IOException {
        List<Path> files=FilesEx.walk(root,p->p.toString().endsWith(".java")&&
            (p.toString().contains("test")||p.getFileName().toString().endsWith("Test.java")));
        List<TestFinding> findings=new ArrayList<>();int tests=0,assertions=0,disabled=0;
        List<TestFile> metrics=new ArrayList<>();
        for(Path file:files){
            String code=Files.readString(file);
            int methods=count(code,"@(Test|ParameterizedTest|RepeatedTest)\\b");tests+=methods;
            int fileAssertions=count(code,"\\b(assert[A-Z]\\w*|Assertions\\.|TestSupport\\.|verify\\s*\\()");assertions+=fileAssertions;
            int fileDisabled=count(code,"@(Disabled|Ignore)\\b");disabled+=fileDisabled;
            String relative=root.relativize(file).toString();
            if(methods>0&&fileAssertions==0)findings.add(new TestFinding("NO_ASSERTIONS","HIGH",relative,1,
                "Arquivo possui testes sem assertions detectáveis."));
            inspect(code,relative,findings);
            metrics.add(new TestFile(relative,methods,fileAssertions,fileDisabled,
                count(code,"Thread\\.sleep\\s*\\("),count(code,"@(BeforeEach|BeforeAll)\\b")));
        }
        double ratio=tests==0?0:(double)assertions/tests;
        return new TestReport(files.size(),tests,assertions,disabled,ratio,metrics,findings,
            tests>0&&findings.stream().noneMatch(f->f.severity().equals("HIGH")));
    }

    public ConfigReport configurations(Path first,Path second) throws IOException {
        Map<String,ConfigValue> left=readConfigurations(first),right=second==null?Map.of():readConfigurations(second);
        List<ConfigDifference> differences=new ArrayList<>();List<TestFinding> findings=new ArrayList<>();
        left.forEach((key,value)->{
            if(!right.isEmpty()&&!right.containsKey(key))differences.add(new ConfigDifference("ONLY_FIRST",key,value.value(),""));
            else if(right.containsKey(key)&&!Objects.equals(value.value(),right.get(key).value()))
                differences.add(new ConfigDifference("CHANGED",key,redact(key,value.value()),redact(key,right.get(key).value())));
            inspectConfig(key,value,findings);
        });
        right.forEach((key,value)->{if(!left.containsKey(key))differences.add(new ConfigDifference("ONLY_SECOND",key,"",value.value()));});
        return new ConfigReport(first,second,left.size(),right.size(),differences,findings,
            findings.stream().noneMatch(f->Set.of("HIGH","CRITICAL").contains(f.severity())));
    }

    public ReleaseReport release(Path root) throws IOException {
        JavaQualityAnalyzer.Report quality=new JavaQualityAnalyzer().analyze(root);
        SecurityScanner.Report security=new SecurityScanner().scan(root);
        DependencyAuditor.Report dependencies=new DependencyAuditor().analyze(root,Set.of(),Set.of());
        TechnicalDebtTracker.Report debt=new TechnicalDebtTracker().scan(root);
        TestReport tests=tests(root);
        SpringGovernanceAnalyzer.Report spring=new SpringGovernanceAnalyzer().analyze(root);
        List<Gate> gates=List.of(
            new Gate("QUALITY",quality.qualityScore()>=60,"Score "+quality.qualityScore()+" (mínimo 60)"),
            new Gate("SECURITY",security.passed(),"Findings: "+security.findings().size()),
            new Gate("DEPENDENCIES",dependencies.findings().stream().noneMatch(f->f.severity().equals("HIGH")),
                "Findings: "+dependencies.findings().size()),
            new Gate("DEBT",debt.passed(),"Score: "+debt.score()),
            new Gate("TESTS",tests.passed(),"Testes detectados: "+tests.tests()),
            new Gate("SPRING",spring.findings().stream().noneMatch(f->f.severity().equals("HIGH")),
                "Findings: "+spring.findings().size()));
        long passed=gates.stream().filter(Gate::passed).count();
        int score=(int)Math.round(100.0*passed/gates.size());
        List<String> recommendations=gates.stream().filter(g->!g.passed()).map(g->"Corrija o gate "+g.name()+": "+g.detail()).toList();
        return new ReleaseReport(score,passed,gates.size(),passed==gates.size(),gates,recommendations,
            Map.of("qualityFindings",quality.findings().size(),"securityFindings",security.findings().size(),
                "dependencyFindings",dependencies.findings().size(),"debtItems",debt.items().size(),
                "tests",tests.tests(),"springFindings",spring.findings().size()));
    }

    private Map<String,ConfigValue> readConfigurations(Path root)throws IOException{
        Map<String,ConfigValue> result=new TreeMap<>();
        for(Path file:FilesEx.walk(root,p->{String n=p.getFileName().toString();return n.endsWith(".properties")||
            n.endsWith(".yml")||n.endsWith(".yaml")||n.equals(".env");})){
            Map<String,String> values;
            if(file.toString().endsWith(".yml")||file.toString().endsWith(".yaml"))values=CliConfig.parseYaml(file);
            else{values=new LinkedHashMap<>();for(String line:Files.readAllLines(file)){
                String clean=line.strip();if(clean.isBlank()||clean.startsWith("#"))continue;int split=Math.max(clean.indexOf('='),clean.indexOf(':'));
                if(split>0)values.put(clean.substring(0,split).trim(),clean.substring(split+1).trim());}}
            String prefix=root.relativize(file).toString()+":";
            values.forEach((key,value)->result.put(prefix+key,new ConfigValue(value,file.toString())));
        }return result;
    }
    private void inspect(String code,String file,List<TestFinding> findings){
        Matcher sleep=Pattern.compile("Thread\\.sleep\\s*\\(").matcher(code);while(sleep.find())findings.add(new TestFinding(
            "BLOCKING_SLEEP","MEDIUM",file,line(code,sleep.start()),"Teste usa Thread.sleep; prefira espera determinística."));
        Matcher port=Pattern.compile("(localhost|127\\.0\\.0\\.1):(\\d{2,5})").matcher(code);while(port.find())findings.add(new TestFinding(
            "FIXED_PORT","LOW",file,line(code,port.start()),"Porta fixa pode causar flakiness: "+port.group(2)));
        Matcher now=Pattern.compile("(LocalDate|Instant|LocalDateTime)\\.now\\s*\\(").matcher(code);while(now.find())findings.add(new TestFinding(
            "SYSTEM_CLOCK","LOW",file,line(code,now.start()),"Relógio do sistema reduz determinismo; injete Clock."));
        if(code.contains("@TestMethodOrder"))findings.add(new TestFinding("ORDER_DEPENDENCY","MEDIUM",file,1,
            "Ordem explícita pode esconder dependência entre testes."));
    }
    private void inspectConfig(String key,ConfigValue value,List<TestFinding> findings){
        String lower=key.toLowerCase(Locale.ROOT),content=value.value();
        if(lower.matches(".*(password|secret|token|api.?key).*")&&!content.isBlank()&&!content.matches("\\$\\{.*}|\\$\\(.*\\)|<.*>"))
            findings.add(new TestFinding("LITERAL_SECRET","CRITICAL",value.file(),1,"Possível segredo literal em "+key));
        if(lower.contains("show-sql")&&content.equalsIgnoreCase("true"))findings.add(new TestFinding("SHOW_SQL","LOW",value.file(),1,"show-sql habilitado."));
        if(lower.contains("ddl-auto")&&Set.of("create","create-drop","update").contains(content.toLowerCase(Locale.ROOT)))
            findings.add(new TestFinding("DDL_AUTO","HIGH",value.file(),1,"ddl-auto inseguro para produção: "+content));
        if(lower.contains("exposure.include")&&content.contains("*"))findings.add(new TestFinding("ACTUATOR_ALL","HIGH",value.file(),1,"Actuator expõe todos os endpoints."));
    }
    private String redact(String key,String value){return key.toLowerCase(Locale.ROOT).matches(".*(password|secret|token|key).*")?"***":value;}
    private int count(String value,String regex){int count=0;Matcher m=Pattern.compile(regex).matcher(value);while(m.find())count++;return count;}
    private int line(String value,int offset){return(int)value.substring(0,offset).lines().count();}
    private record ConfigValue(String value,String file){}
    public record TestFinding(String kind,String severity,String file,int line,String description){}
    public record TestFile(String file,int tests,int assertions,int disabled,int sleeps,int fixtures){}
    public record TestReport(int files,int tests,int assertions,int disabled,double assertionsPerTest,
        List<TestFile> metrics,List<TestFinding> findings,boolean passed){}
    public record ConfigDifference(String kind,String key,String first,String second){}
    public record ConfigReport(Path first,Path second,int firstEntries,int secondEntries,
        List<ConfigDifference> differences,List<TestFinding> findings,boolean passed){}
    public record Gate(String name,boolean passed,String detail){}
    public record ReleaseReport(int score,long passedGates,int totalGates,boolean ready,List<Gate> gates,
        List<String> recommendations,Map<String,Object> summary){}
}
