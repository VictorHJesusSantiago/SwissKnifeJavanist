package dev.swissknife.portal;

import com.sun.net.httpserver.*;
import dev.swissknife.server.HttpSupport;
import dev.swissknife.util.Json;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

/** Portal web local, responsivo e sem dependências externas. */
public final class PortalServer {
    private final HttpServer server;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String vulnerabilityUrl;
    private final String itamUrl;
    private final JobManager jobs;

    public PortalServer(int port, String vulnerabilityUrl, String itamUrl) throws IOException {
        this(port,vulnerabilityUrl,itamUrl,Path.of(System.getenv().getOrDefault("SWISSKNIFE_JOBS_DB","data/portal-jobs.db")));
    }
    public PortalServer(int port, String vulnerabilityUrl, String itamUrl,Path jobsDatabase) throws IOException {
        this.vulnerabilityUrl = vulnerabilityUrl;
        this.itamUrl = itamUrl;
        this.jobs=new JobManager(jobsDatabase,integerEnv("SWISSKNIFE_JOB_CONCURRENCY",4),
            integerEnv("SWISSKNIFE_JOB_HISTORY",500));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::page);
        server.createContext("/app.css", e -> text(e, "text/css; charset=utf-8", CSS));
        server.createContext("/app.js", e -> text(e, "text/javascript; charset=utf-8", JS));
        server.createContext("/manifest.webmanifest", e -> text(e, "application/manifest+json", MANIFEST));
        server.createContext("/service-worker.js", e -> text(e, "text/javascript", SERVICE_WORKER));
        server.createContext("/api/status", e -> HttpSupport.json(e, 200, status()));
        server.createContext("/api/jobs",HttpSupport.authenticated(this::jobApi));
        server.createContext("/api/jobs-events",HttpSupport.authenticated(this::jobEvents));
        server.createContext("/api/metrics",HttpSupport.authenticated(e->HttpSupport.json(e,200,jobs.metrics())));
        server.createContext("/metrics",HttpSupport.authenticated(e->text(e,"text/plain; version=0.0.4; charset=utf-8",prometheus())));
        server.createContext("/health", e -> HttpSupport.json(e, 200, Map.of("status", "UP", "service", "portal")));
    }
    public void start() { server.start(); }
    public void stop() { server.stop(1); jobs.close(); }
    public int port() { return server.getAddress().getPort(); }

    private void page(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            HttpSupport.json(exchange, 404, Map.of("error", "Recurso não encontrado")); return;
        }
        text(exchange, "text/html; charset=utf-8", HTML);
    }
    private Map<String, Object> status() {
        return Map.of("vulnerabilities", fetch(vulnerabilityUrl), "itam", fetch(itamUrl),
            "portal", Map.of("status", "UP"));
    }
    private String prometheus(){
        JobManager.Metrics m=jobs.metrics();
        return """
            # HELP swissknife_jobs_submitted_total Jobs submetidos desde o início do processo.
            # TYPE swissknife_jobs_submitted_total counter
            swissknife_jobs_submitted_total %d
            # TYPE swissknife_jobs_completed_total counter
            swissknife_jobs_completed_total %d
            # TYPE swissknife_jobs_failed_total counter
            swissknife_jobs_failed_total %d
            # TYPE swissknife_jobs_cancelled_total counter
            swissknife_jobs_cancelled_total %d
            # TYPE swissknife_jobs_running gauge
            swissknife_jobs_running %d
            # TYPE swissknife_jobs_queued gauge
            swissknife_jobs_queued %d
            # TYPE swissknife_jobs_duration_milliseconds gauge
            swissknife_jobs_duration_milliseconds %.3f
            """.formatted(m.submitted(),m.completed(),m.failed(),m.cancelled(),m.running(),m.queued(),m.averageDurationMs());
    }
    private void jobApi(HttpExchange exchange)throws IOException{
        String path=exchange.getRequestURI().getPath();
        String suffix=path.length()>"/api/jobs".length()?path.substring("/api/jobs".length()).replaceFirst("^/",""):"";
        try{
            switch(exchange.getRequestMethod()){
                case"GET"->{
                    if(suffix.isBlank())HttpSupport.json(exchange,200,jobs.list(
                        parseInt(HttpSupport.query(exchange).get("limit"),100),HttpSupport.query(exchange).get("state")));
                    else if(suffix.endsWith("/artifact")) artifact(exchange,suffix.substring(0,suffix.length()-"/artifact".length()));
                    else jobs.find(suffix).ifPresentOrElse(job->send(exchange,200,job),()->send(exchange,404,Map.of("error","Job não encontrado")));
                }
                case"POST"->{
                    if(!suffix.isBlank()){HttpSupport.json(exchange,404,Map.of("error","Rota inválida"));return;}
                    Map<String,Object> body=HttpSupport.body(exchange);
                    if(!(body.get("command") instanceof List<?> list)||list.isEmpty())
                        throw new IllegalArgumentException("command deve ser uma lista não vazia");
                    HttpSupport.json(exchange,202,jobs.submit(list.stream().map(String::valueOf).toList()));
                }
                case"DELETE"->{
                    if(suffix.isBlank())throw new IllegalArgumentException("ID do job é obrigatório");
                    HttpSupport.json(exchange,200,jobs.cancel(suffix));
                }
                default->HttpSupport.json(exchange,405,Map.of("error","Método não permitido"));
            }
        }catch(IllegalArgumentException e){HttpSupport.json(exchange,400,Map.of("error",e.getMessage()));}
    }
    private void artifact(HttpExchange exchange,String jobId) throws IOException{
        var path=jobs.artifactPath(jobId);
        if(path.isEmpty()){HttpSupport.json(exchange,404,Map.of("error","Artefato não encontrado para este job"));return;}
        byte[] bytes=Files.readAllBytes(path.get());
        String name=path.get().getFileName().toString().toLowerCase(Locale.ROOT);
        String contentType=name.endsWith(".html")?"text/html; charset=utf-8":name.endsWith(".json")?"application/json"
            :name.endsWith(".md")?"text/markdown; charset=utf-8":name.endsWith(".xml")||name.endsWith(".mmd")?"text/plain; charset=utf-8"
            :"application/octet-stream";
        exchange.getResponseHeaders().set("Content-Type",contentType);
        exchange.getResponseHeaders().set("Content-Disposition","inline; filename=\""+path.get().getFileName()+"\"");
        exchange.sendResponseHeaders(200,bytes.length);
        try(var out=exchange.getResponseBody()){out.write(bytes);}
    }
    private static final int MAX_SSE_CONNECTIONS_PER_CLIENT=
        integerEnv("SWISSKNIFE_MAX_SSE_PER_CLIENT",3);
    private final java.util.concurrent.ConcurrentMap<String,java.util.concurrent.atomic.AtomicInteger> sseConnections=
        new java.util.concurrent.ConcurrentHashMap<>();
    /** Atualização em tempo real da lista de jobs via Server-Sent Events (substitui o polling). */
    private void jobEvents(HttpExchange exchange) throws IOException{
        String clientId=exchange.getRemoteAddress()==null?"unknown":exchange.getRemoteAddress().getAddress().getHostAddress();
        var active=sseConnections.computeIfAbsent(clientId,ignored->new java.util.concurrent.atomic.AtomicInteger());
        if(active.incrementAndGet()>MAX_SSE_CONNECTIONS_PER_CLIENT){
            active.decrementAndGet();
            HttpSupport.problem(exchange,429,"Limite de conexões simultâneas excedido","Máximo de "+MAX_SSE_CONNECTIONS_PER_CLIENT+" streams por cliente");
            return;
        }
        try{
            exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control","no-cache");
            exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()){
                long deadline=System.currentTimeMillis()+Duration.ofMinutes(10).toMillis();
                while(System.currentTimeMillis()<deadline){
                    String payload=Json.stringify(jobs.list(50,null));
                    out.write(("data: "+payload+"\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                }
            } catch(IOException clientDisconnected){ /* cliente fechou a conexão; encerra o stream silenciosamente */ }
        } finally { active.decrementAndGet(); }
    }
    private void send(HttpExchange exchange,int status,Object value){try{HttpSupport.json(exchange,status,value);}catch(IOException e){throw new RuntimeException(e);}}
    private int parseInt(String value,int fallback){try{return value==null?fallback:Integer.parseInt(value);}catch(Exception e){return fallback;}}
    private static int integerEnv(String name,int fallback){try{String value=System.getenv(name);return value==null?fallback:Integer.parseInt(value);}catch(Exception e){return fallback;}}
    private Object fetch(String base) {
        try {
            var request = HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(3)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Json.parse(response.body())
                : Map.of("status", "DOWN", "httpStatus", response.statusCode());
        } catch (Exception e) { return Map.of("status", "DOWN", "reason", e.getClass().getSimpleName()); }
    }
    private void text(HttpExchange exchange, String contentType, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy",
            "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static final String HTML = """
        <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="theme-color" content="#14213d"><link rel="manifest" href="/manifest.webmanifest">
        <link rel="stylesheet" href="/app.css"><title>SwissKnife Javanist</title></head>
        <body><header><div><span class="mark">SK</span><strong>SwissKnife Javanist</strong></div>
        <button id="theme" aria-label="Alternar tema">◐</button></header>
        <main><section class="hero"><p class="eyebrow">GOVERNANÇA JAVA</p>
        <h1>Um painel, todas as arestas.</h1><p>Estado local dos serviços e acesso rápido às ferramentas.</p></section>
        <section aria-labelledby="services"><h2 id="services">Serviços</h2><div id="cards" class="grid" aria-live="polite">
        <article class="card skeleton">Carregando…</article></div></section>
        <section aria-labelledby="execution"><h2 id="execution">Executar análise</h2>
        <form id="job-form" class="runner card">
        <label>Ferramenta<select id="tool"><option value="quality">Qualidade</option><option value="security-scan">Segurança</option>
        <option value="dependency-audit">Dependências/SBOM</option><option value="spring-audit">Spring Boot</option>
        <option value="test-audit">Testes</option><option value="release-readiness">Prontidão de release</option>
        <option value="debt">Dívida técnica</option><option value="modernize">Modernização</option>
        <option value="detect-pii">Detectar PII</option><option value="contract-test">Teste de contrato</option>
        <option value="slow-query-file">Analisar SQL (lote)</option><option value="jvm-diagnose">Diagnóstico JVM</option></select></label>
        <label>Caminho ou argumento principal<input id="target" value="." required></label>
        <label>Token da API (mantido somente nesta aba)<input id="api-token" type="password" autocomplete="off"></label>
        <button type="submit" class="primary">Executar</button><span id="feedback" role="status"></span></form></section>
        <section aria-labelledby="jobs-title"><div class="section-title"><h2 id="jobs-title">Execuções</h2>
        <button id="refresh">Atualizar</button></div>
        <input id="job-search" placeholder="Filtrar execuções por comando" aria-label="Filtrar execuções">
        <div id="jobs" class="jobs" aria-live="polite"></div>
        <pre id="result" tabindex="0" hidden></pre></section>
        <section><h2>Ferramentas</h2><div class="grid tools">
        <article class="card"><b>Qualidade</b><code>swissknife quality .</code></article>
        <article class="card"><b>Segurança</b><code>swissknife security-scan .</code></article>
        <article class="card"><b>Dependências e SBOM</b><code>swissknife dependency-audit .</code></article>
        <article class="card"><b>Spring Boot</b><code>swissknife spring-audit .</code></article>
        <article class="card"><b>Modernização</b><code>swissknife modernize .</code></article>
        <article class="card"><b>Diagnóstico JVM</b><code>swissknife jvm-diagnose arquivo.log</code></article>
        <article class="card"><b>Prontidão de release</b><code>swissknife release-readiness .</code></article>
        <article class="card"><b>Auditoria de testes</b><code>swissknife test-audit .</code></article>
        <article class="card"><b>Vulnerabilidades</b><code>swissknife vuln-report data/vulnerabilities.db</code></article>
        <article class="card"><b>Inventário</b><code>swissknife itam-report data/assets.db</code></article>
        <article class="card"><b>Backup e integridade</b><code>swissknife store-admin data/assets.db verify</code></article>
        <article class="card"><b>Assistência por IA</b><code>swissknife ai-assist config.properties explain report.json</code></article>
        </div></section></main><footer>Executado localmente · sem CDN · sem rastreamento</footer>
        <script src="/app.js"></script></body></html>
        """;
    private static final String CSS = """
        :root{color-scheme:light;--bg:#f6f3ea;--ink:#14213d;--card:#fff;--line:#d8d2c3;--accent:#e76f51}
        :root.dark{color-scheme:dark;--bg:#101827;--ink:#f5f0e6;--card:#192438;--line:#354158;--accent:#ff8b6b}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px system-ui,sans-serif}
        header{display:flex;justify-content:space-between;align-items:center;padding:1rem max(1rem,calc((100% - 1100px)/2));border-bottom:1px solid var(--line)}
        .mark{display:inline-grid;place-items:center;background:var(--ink);color:var(--bg);width:2.4rem;height:2.4rem;border-radius:.6rem;margin-right:.7rem}
        button{font:inherit;border:1px solid var(--line);background:var(--card);color:var(--ink);border-radius:50%;width:2.5rem;height:2.5rem}
        main{max-width:1100px;margin:auto;padding:3rem 1rem}.hero{max-width:720px;margin-bottom:3rem}.eyebrow{letter-spacing:.16em;font-weight:700;color:var(--accent)}
        h1{font-size:clamp(2.5rem,7vw,5.5rem);line-height:.95;margin:.4rem 0 1.2rem}h2{margin-top:2.5rem}
        .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:1rem}.card{background:var(--card);border:1px solid var(--line);border-radius:1rem;padding:1.2rem;box-shadow:0 8px 30px #0000000b}
        .status{display:flex;align-items:center;gap:.5rem;margin-top:.7rem}.dot{width:.7rem;height:.7rem;border-radius:50%;background:#d62828}.up .dot{background:#2a9d8f}
        code{display:block;margin-top:.8rem;padding:.65rem;background:var(--bg);border-radius:.5rem;overflow:auto}footer{text-align:center;padding:2rem;color:color-mix(in srgb,var(--ink) 60%,transparent)}
        .runner{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:1rem;align-items:end}.runner label{display:grid;gap:.35rem;font-weight:650}
        input,select{font:inherit;padding:.7rem;border:1px solid var(--line);border-radius:.55rem;background:var(--bg);color:var(--ink)}
        button.primary{width:auto;border-radius:.6rem;padding:.7rem 1rem;background:var(--ink);color:var(--bg)}.section-title{display:flex;justify-content:space-between;align-items:center}
        .jobs{display:grid;gap:.6rem}.job{display:grid;grid-template-columns:1fr auto;gap:.5rem;padding:.8rem;border-bottom:1px solid var(--line)}
        .job small{display:block;opacity:.7}.job button{width:auto;height:auto;border-radius:.5rem;padding:.35rem .6rem}pre{padding:1rem;background:var(--card);border:1px solid var(--line);border-radius:.8rem;overflow:auto;max-height:30rem}
        """;
    private static final String JS = """
        const root=document.documentElement;
        if(localStorage.theme==='dark')root.classList.add('dark');
        document.querySelector('#theme').onclick=()=>{root.classList.toggle('dark');localStorage.theme=root.classList.contains('dark')?'dark':'light'};
        const label=s=>s==='vulnerabilities'?'Vulnerabilidades':s==='itam'?'Ativos de TI':'Portal';
        fetch('/api/status').then(r=>r.json()).then(data=>{
          cards.innerHTML=Object.entries(data).map(([name,value])=>`<article class="card ${value.status==='UP'?'up':''}">
          <b>${label(name)}</b><div class="status"><span class="dot"></span>${value.status||'DESCONHECIDO'}</div></article>`).join('');
        }).catch(()=>cards.innerHTML='<article class="card">Não foi possível consultar os serviços.</article>');
        const auth=()=>{const token=document.querySelector('#api-token').value;sessionStorage.apiToken=token;
          return token?{'Authorization':'Bearer '+token}:{}};
        document.querySelector('#api-token').value=sessionStorage.apiToken||'';
        const escapeHtml=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
        let lastJobs=[];
        function renderJobs(){
          const filter=(document.querySelector('#job-search').value||'').toLowerCase();
          const data=lastJobs.filter(job=>job.command.join(' ').toLowerCase().includes(filter));
          jobs.innerHTML=data.length?data.map(job=>`<article class="job" data-id="${job.id}">
          <div><b>${escapeHtml(job.command.join(' '))}</b><small>${job.state} · ${job.durationMs} ms · ${escapeHtml(job.message||job.error||'')}</small></div>
          <div><button data-view="${job.id}">Ver</button>${job.state==='COMPLETED'?` <a href="/api/jobs/${job.id}/artifact" target="_blank" data-artifact="${job.id}">Artefato</a>`:''}${['QUEUED','RUNNING'].includes(job.state)?` <button data-cancel="${job.id}">Cancelar</button>`:''}</div></article>`).join(''):
          '<p>Nenhuma execução registrada.</p>';
        }
        async function loadJobs(){
          try{const response=await fetch('/api/jobs?limit=50',{headers:auth()});if(!response.ok)throw new Error('HTTP '+response.status);
          lastJobs=await response.json();renderJobs();}catch(error){jobs.innerHTML='<p>Não autorizado ou serviço indisponível.</p>'}}
        document.querySelector('#job-search').addEventListener('input',renderJobs);
        document.querySelector('#job-form').addEventListener('submit',async event=>{event.preventDefault();feedback.textContent='Enviando…';
          const response=await fetch('/api/jobs',{method:'POST',headers:{'Content-Type':'application/json',...auth()},
          body:JSON.stringify({command:[tool.value,target.value]})});const data=await response.json();
          feedback.textContent=response.ok?'Job criado: '+data.id:(data.error||'Falha');await loadJobs()});
        jobs.addEventListener('click',async event=>{const view=event.target.dataset.view,cancel=event.target.dataset.cancel;
          if(view){const response=await fetch('/api/jobs/'+view,{headers:auth()});const data=await response.json();result.hidden=false;result.textContent=JSON.stringify(data,null,2)}
          if(cancel){await fetch('/api/jobs/'+cancel,{method:'DELETE',headers:auth()});await loadJobs()}});
        refresh.onclick=loadJobs;loadJobs();
        async function streamJobs(){
          try{
            const response=await fetch('/api/jobs-events',{headers:auth()});
            if(!response.ok||!response.body)throw new Error('SSE indisponível');
            const reader=response.body.getReader();const decoder=new TextDecoder();let buffer='';
            while(true){
              const {value,done}=await reader.read();if(done)break;
              buffer+=decoder.decode(value,{stream:true});
              let index;while((index=buffer.indexOf('\n\n'))>=0){
                const chunk=buffer.slice(0,index);buffer=buffer.slice(index+2);
                if(chunk.startsWith('data: ')){lastJobs=JSON.parse(chunk.slice(6));renderJobs()}
              }
            }
          }catch(error){/* volta para polling */}
          setInterval(loadJobs,5000);
        }
        streamJobs();
        if('serviceWorker' in navigator)navigator.serviceWorker.register('/service-worker.js');
        """;
    private static final String MANIFEST = """
        {"name":"SwissKnife Javanist","short_name":"SwissKnife","start_url":"/",
         "display":"standalone","background_color":"#f6f3ea","theme_color":"#14213d"}
        """;
    private static final String SERVICE_WORKER = """
        const CACHE='swissknife-v1',ASSETS=['/','/app.css','/app.js','/manifest.webmanifest'];
        self.addEventListener('install',e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS))));
        self.addEventListener('fetch',e=>{if(e.request.url.includes('/api/'))return;e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request)))});
        """;
}
