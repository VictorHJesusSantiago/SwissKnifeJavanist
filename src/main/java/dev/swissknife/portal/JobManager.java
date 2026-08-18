package dev.swissknife.portal;

import dev.swissknife.cli.CommandExecutor;
import dev.swissknife.server.JsonStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Executa somente comandos de análise permitidos, com fila, cancelamento e histórico. */
public final class JobManager implements AutoCloseable {
    private static final Set<String> ALLOWED=Set.of("docs","docs-site","docs-diff","deps","deps-export",
        "slow-query","slow-query-file","slow-query-log","schema-diff","schema-script","detect-pii",
        "contract-test","gatling","gatling-project","debt","dependency-audit","sbom","quality",
        "security-scan","spring-audit","test-audit","config-audit","release-readiness","modernize",
        "jvm-diagnose","vuln-report","itam-report");
    private final ExecutorService executor;
    /**
     * Concorrência limitada por Semaphore sobre virtual-thread-per-task, e não por
     * newFixedThreadPool com fábrica de virtual threads. Um pool fixo de virtual threads mantém N
     * threads permanentemente bloqueadas na fila do pool, o que anula a razão de ser das virtual
     * threads e ainda transforma a fila em um buffer ilimitado sem backpressure.
     */
    private final Semaphore slots;
    private final CommandExecutor commandExecutor;
    private final ConcurrentMap<String,MutableJob> jobs=new ConcurrentHashMap<>();
    private final JsonStore store;
    private final int maxHistory;
    private final AtomicLong submitted=new AtomicLong(),completed=new AtomicLong(),failed=new AtomicLong(),cancelled=new AtomicLong();

    public JobManager(Path database,int concurrency,int maxHistory,CommandExecutor commandExecutor)throws IOException{
        this.commandExecutor=Objects.requireNonNull(commandExecutor,"commandExecutor é obrigatório");
        this.slots=new Semaphore(Math.max(1,Math.min(concurrency,32)));
        this.executor=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("swissknife-job-",0).factory());
        store=new JsonStore(database);this.maxHistory=Math.max(10,maxHistory);
        for(Map<String,Object> saved:store.all()){
            MutableJob job=MutableJob.restore(saved);
            if(job.state==State.QUEUED||job.state==State.RUNNING){job.state=State.FAILED;job.error="Processo interrompido durante reinicialização";job.finishedAt=Instant.now();}
            jobs.put(job.id,job);
        }
        trim();
    }
    public Job submit(List<String> command){
        validate(command);
        MutableJob job=new MutableJob(UUID.randomUUID().toString(),List.copyOf(command));
        jobs.put(job.id,job);submitted.incrementAndGet();persist(job);
        job.future=executor.submit(()->run(job));trim();return job.snapshot();
    }
    public List<Job> list(int limit,String state){
        return jobs.values().stream().filter(job->state==null||state.isBlank()||job.state.name().equalsIgnoreCase(state))
            .sorted(Comparator.comparing((MutableJob job)->job.createdAt).reversed())
            .limit(Math.max(1,Math.min(limit,1000))).map(MutableJob::snapshot).toList();
    }
    public Optional<Job> find(String id){MutableJob job=jobs.get(id);return job==null?Optional.empty():Optional.of(job.snapshot());}
    private static final Path ARTIFACT_BASE=Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    /** Extrai o caminho do artefato gerado por um job concluído (campo "output" do resultado), se houver. */
    public Optional<Path> artifactPath(String id){
        MutableJob job=jobs.get(id);
        if(job==null||job.state!=State.COMPLETED||job.result==null)return Optional.empty();
        Object normalized=dev.swissknife.util.Json.parse(dev.swissknife.util.Json.stringify(job.result));
        if(!(normalized instanceof Map<?,?> map))return Optional.empty();
        Object output=map.get("output");
        if(output==null)return Optional.empty();
        Path candidate;
        try{candidate=Path.of(String.valueOf(output)).toAbsolutePath().normalize();}
        catch(Exception e){return Optional.empty();}
        if(!candidate.startsWith(ARTIFACT_BASE))return Optional.empty();
        return java.nio.file.Files.isRegularFile(candidate)?Optional.of(candidate):Optional.empty();
    }
    public Job cancel(String id){
        MutableJob job=jobs.get(id);if(job==null)throw new IllegalArgumentException("Job não encontrado: "+id);
        synchronized(job){
            if(job.terminal())return job.snapshot();
            boolean stopped=job.future!=null&&job.future.cancel(true);
            if(stopped){job.state=State.CANCELLED;job.finishedAt=Instant.now();job.message="Cancelado pelo usuário";cancelled.incrementAndGet();persist(job);}
            return job.snapshot();
        }
    }
    public Metrics metrics(){
        long running=jobs.values().stream().filter(job->job.state==State.RUNNING).count();
        long queued=jobs.values().stream().filter(job->job.state==State.QUEUED).count();
        double average=jobs.values().stream().filter(MutableJob::terminal).mapToLong(MutableJob::durationMs).average().orElse(0);
        return new Metrics(submitted.get(),completed.get(),failed.get(),cancelled.get(),running,queued,average,jobs.size());
    }
    private void run(MutableJob job){
        try{ slots.acquire(); }
        catch(InterruptedException e){
            Thread.currentThread().interrupt();
            synchronized(job){
                if(!job.terminal()){job.state=State.CANCELLED;job.message="Cancelado antes de iniciar";
                    job.finishedAt=Instant.now();cancelled.incrementAndGet();persist(job);}
            }
            return;
        }
        try{
            synchronized(job){if(job.terminal())return;job.state=State.RUNNING;job.startedAt=Instant.now();job.message="Executando";persist(job);}
            try{
                Object result=commandExecutor.execute(job.command.toArray(String[]::new));
                synchronized(job){job.result=result;job.state=State.COMPLETED;job.progress=100;job.message="Concluído";job.finishedAt=Instant.now();completed.incrementAndGet();persist(job);}
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();synchronized(job){job.state=State.CANCELLED;job.error="Interrompido";job.finishedAt=Instant.now();cancelled.incrementAndGet();persist(job);}
            }catch(Throwable e){
                synchronized(job){job.state=State.FAILED;job.error=safe(e);job.message="Falha";job.finishedAt=Instant.now();failed.incrementAndGet();persist(job);}
            }
        } finally { slots.release(); }
    }
    private void validate(List<String> command){
        if(command==null||command.isEmpty())throw new IllegalArgumentException("Comando vazio");
        if(!ALLOWED.contains(command.getFirst()))throw new IllegalArgumentException("Comando não permitido no portal: "+command.getFirst());
        if(command.size()>30)throw new IllegalArgumentException("Máximo de 30 argumentos");
        for(String arg:command)if(arg==null||arg.length()>4096||arg.indexOf('\0')>=0)throw new IllegalArgumentException("Argumento inválido");
    }
    private void persist(MutableJob job){
        try{store.save(job.map());}catch(IOException e){System.err.println("Falha ao persistir job "+job.id+": "+e.getMessage());}
    }
    private void trim(){
        List<MutableJob> ordered=jobs.values().stream().filter(MutableJob::terminal)
            .sorted(Comparator.comparing((MutableJob job)->job.createdAt).reversed()).toList();
        for(int i=maxHistory;i<ordered.size();i++){MutableJob job=ordered.get(i);jobs.remove(job.id);try{store.delete(job.id);}catch(IOException ignored){}}
    }
    private String safe(Throwable e){String message=e.getMessage();return e.getClass().getSimpleName()+(message==null?"":": "+message.substring(0,Math.min(message.length(),2000)));}
    @Override public void close(){executor.shutdownNow();}

    public enum State{QUEUED,RUNNING,COMPLETED,FAILED,CANCELLED}
    public record Job(String id,List<String> command,State state,int progress,String message,String error,
        Object result,String createdAt,String startedAt,String finishedAt,long durationMs){}
    public record Metrics(long submitted,long completed,long failed,long cancelled,long running,long queued,
        double averageDurationMs,long retainedJobs){}

    private static final class MutableJob{
        final String id;final List<String> command;volatile State state=State.QUEUED;volatile int progress;
        volatile String message="Na fila",error="";volatile Object result;final Instant createdAt;
        volatile Instant startedAt,finishedAt;volatile Future<?> future;
        MutableJob(String id,List<String> command){this(id,command,Instant.now());}
        MutableJob(String id,List<String> command,Instant createdAt){this.id=id;this.command=command;this.createdAt=createdAt;}
        boolean terminal(){return state==State.COMPLETED||state==State.FAILED||state==State.CANCELLED;}
        long durationMs(){Instant end=finishedAt==null?Instant.now():finishedAt;Instant start=startedAt==null?createdAt:startedAt;return Duration.between(start,end).toMillis();}
        Job snapshot(){return new Job(id,command,state,progress,message,error,result,createdAt.toString(),
            startedAt==null?"":startedAt.toString(),finishedAt==null?"":finishedAt.toString(),durationMs());}
        Map<String,Object> map(){Map<String,Object> map=new LinkedHashMap<>();map.put("id",id);map.put("command",command);
            map.put("state",state.name());map.put("progress",progress);map.put("message",message);map.put("error",error);
            map.put("result",result);map.put("createdAt",createdAt.toString());map.put("startedAt",startedAt==null?"":startedAt.toString());
            map.put("finishedAt",finishedAt==null?"":finishedAt.toString());map.put("durationMs",durationMs());return map;}
        @SuppressWarnings("unchecked") static MutableJob restore(Map<String,Object> map){
            List<String> command=map.get("command") instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();
            MutableJob job=new MutableJob(String.valueOf(map.get("id")),command,Instant.parse(String.valueOf(map.get("createdAt"))));
            try{job.state=State.valueOf(String.valueOf(map.get("state")));}catch(Exception ignored){job.state=State.FAILED;}
            job.progress=map.get("progress") instanceof Number n?n.intValue():0;job.message=String.valueOf(map.getOrDefault("message",""));
            job.error=String.valueOf(map.getOrDefault("error",""));job.result=map.get("result");
            job.startedAt=parse(map.get("startedAt"));job.finishedAt=parse(map.get("finishedAt"));return job;
        }
        static Instant parse(Object value){try{String text=String.valueOf(value);return text.isBlank()?null:Instant.parse(text);}catch(Exception e){return null;}}
    }
}
