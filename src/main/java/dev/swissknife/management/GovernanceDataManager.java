package dev.swissknife.management;

import dev.swissknife.server.JsonStore;
import dev.swissknife.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Importação, deduplicação, workflow e relatórios para vulnerabilidades e ativos. */
public final class GovernanceDataManager {
    public ImportReport importVulnerabilities(Path database, Path input, String format) throws IOException {
        JsonStore store = new JsonStore(database);
        List<Map<String,Object>> candidates = switch(format.toLowerCase(Locale.ROOT)){
            case "sarif" -> sarif(input);
            case "cyclonedx" -> cyclonedx(input);
            case "semgrep" -> semgrep(input);
            case "generic","json" -> generic(input);
            default -> throw new IllegalArgumentException("Formato: sarif, cyclonedx, semgrep ou generic");
        };
        Map<String,Map<String,Object>> existing=new HashMap<>();
        store.all().forEach(item->existing.put(fingerprint(item),item));
        int created=0,updated=0,skipped=0;
        for(Map<String,Object> item:candidates){
            normalizeVulnerability(item,format);
            String fingerprint=fingerprint(item);
            Map<String,Object> old=existing.get(fingerprint);
            if(old==null){item.put("fingerprint",fingerprint);store.save(item);existing.put(fingerprint,item);created++;}
            else{
                boolean changed=false;
                for(var entry:item.entrySet())if(entry.getValue()!=null&&!Objects.equals(old.get(entry.getKey()),entry.getValue())){
                    old.put(entry.getKey(),entry.getValue());changed=true;
                }
                if(changed){old.put("updatedAt",Instant.now().toString());store.save(old);updated++;}else skipped++;
            }
        }
        return new ImportReport(format,candidates.size(),created,updated,skipped,database);
    }

    public WorkflowResult vulnerabilityTransition(Path database,String id,String status,String actor,String comment)
        throws IOException{
        JsonStore store=new JsonStore(database);
        Map<String,Object> item=store.find(id).orElseThrow(()->new IllegalArgumentException("Vulnerabilidade não encontrada: "+id));
        String from=String.valueOf(item.getOrDefault("status","OPEN"));
        String target=status.toUpperCase(Locale.ROOT);
        Map<String,Set<String>> allowed=Map.of(
            "OPEN",Set.of("TRIAGED","IN_PROGRESS","ACCEPTED"),
            "TRIAGED",Set.of("IN_PROGRESS","ACCEPTED","OPEN"),
            "IN_PROGRESS",Set.of("RESOLVED","ACCEPTED","OPEN"),
            "RESOLVED",Set.of("OPEN"),
            "ACCEPTED",Set.of("OPEN","IN_PROGRESS"));
        if(!allowed.getOrDefault(from,Set.of()).contains(target))
            throw new IllegalArgumentException("Transição inválida: "+from+" -> "+target);
        item.put("status",target);item.put("updatedAt",Instant.now().toString());
        if(target.equals("RESOLVED"))item.put("resolvedAt",Instant.now().toString());
        if(target.equals("OPEN"))item.remove("resolvedAt");
        List<Object> history=list(item.get("history"));
        history.add(Map.of("timestamp",Instant.now().toString(),"actor",actor,"from",from,"to",target,
            "comment",comment==null?"":comment));
        item.put("history",history);store.save(item);
        return new WorkflowResult(id,from,target,actor,history.size());
    }

    public VulnerabilityReport vulnerabilityReport(Path database) throws IOException{
        List<Map<String,Object>> all=new JsonStore(database).all();
        Map<String,Long> severity=count(all,"severity"),status=count(all,"status"),project=count(all,"project");
        long overdue=all.stream().filter(this::overdue).count();
        double averageAge=all.stream().mapToLong(item->age(item.get("createdAt"))).average().orElse(0);
        double mttr=all.stream().filter(item->item.get("resolvedAt")!=null).mapToLong(item->
            Math.max(0,ChronoUnit.HOURS.between(instant(item.get("createdAt")),instant(item.get("resolvedAt")))))
            .average().orElse(0);
        List<Map<String,Object>> oldest=all.stream().sorted(Comparator.comparingLong((Map<String,Object> item)->age(item.get("createdAt"))).reversed())
            .limit(20).toList();
        return new VulnerabilityReport(all.size(),overdue,averageAge,mttr,severity,status,project,oldest);
    }

    public ImportReport importAssets(Path database,Path csv) throws IOException{
        List<String> lines=Files.readAllLines(csv);
        if(lines.isEmpty())throw new IllegalArgumentException("CSV vazio");
        List<String> headers=Csv.parseLine(lines.getFirst());
        JsonStore store=new JsonStore(database);
        Map<String,Map<String,Object>> byTag=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        store.all().forEach(asset->byTag.put(String.valueOf(asset.get("tag")),asset));
        int created=0,updated=0,skipped=0;
        for(int row=1;row<lines.size();row++){
            List<String> values=Csv.parseLine(lines.get(row));Map<String,Object> asset=new LinkedHashMap<>();
            for(int i=0;i<Math.min(headers.size(),values.size());i++)asset.put(headers.get(i),values.get(i));
            String tag=String.valueOf(asset.getOrDefault("tag","")).trim();
            if(tag.isBlank())throw new IllegalArgumentException("tag ausente na linha "+(row+1));
            Map<String,Object> old=byTag.get(tag);
            if(old==null){asset.put("status",asset.getOrDefault("status","IN_STOCK"));asset.put("createdAt",Instant.now().toString());
                store.save(asset);byTag.put(tag,asset);created++;}
            else{boolean changed=false;for(var entry:asset.entrySet())if(!entry.getValue().equals(old.get(entry.getKey()))){
                old.put(entry.getKey(),entry.getValue());changed=true;}
                if(changed){old.put("updatedAt",Instant.now().toString());store.save(old);updated++;}else skipped++;}
        }
        return new ImportReport("csv",Math.max(0,lines.size()-1),created,updated,skipped,database);
    }

    public WorkflowResult assetTransition(Path database,String id,String action,String actor,String details)
        throws IOException{
        JsonStore store=new JsonStore(database);
        Map<String,Object> asset=store.find(id).orElseThrow(()->new IllegalArgumentException("Ativo não encontrado: "+id));
        String from=String.valueOf(asset.getOrDefault("status","IN_STOCK"));
        String target=switch(action.toLowerCase(Locale.ROOT)){
            case "checkout","assign"->"IN_USE";case "checkin","return"->"IN_STOCK";
            case "maintenance"->"MAINTENANCE";case "retire","dispose"->"RETIRED";case "lost"->"LOST";
            default->throw new IllegalArgumentException("Ação: checkout, checkin, maintenance, retire ou lost");
        };
        if(action.equalsIgnoreCase("checkout"))asset.put("assignedTo",actor);
        if(action.equalsIgnoreCase("checkin"))asset.remove("assignedTo");
        asset.put("status",target);asset.put("updatedAt",Instant.now().toString());
        List<Object> history=list(asset.get("history"));history.add(Map.of("timestamp",Instant.now().toString(),
            "action",action.toUpperCase(Locale.ROOT),"actor",actor,"from",from,"to",target,
            "details",details==null?"":details));asset.put("history",history);store.save(asset);
        return new WorkflowResult(id,from,target,actor,history.size());
    }

    public AssetReport assetReport(Path database) throws IOException{
        List<Map<String,Object>> all=new JsonStore(database).all();
        Map<String,Long> types=count(all,"type"),statuses=count(all,"status"),departments=count(all,"department");
        double value=all.stream().map(item->number(item.get("purchaseValue"))).mapToDouble(Double::doubleValue).sum();
        long warranty=all.stream().filter(item->{
            LocalDate date=date(item.get("warrantyEnd"));return date!=null&&!date.isBefore(LocalDate.now())&&date.isBefore(LocalDate.now().plusDays(90));
        }).count();
        long unassigned=all.stream().filter(item->String.valueOf(item.getOrDefault("assignedTo","")).isBlank()&&
            String.valueOf(item.getOrDefault("status","")).equals("IN_USE")).count();
        return new AssetReport(all.size(),value,warranty,unassigned,types,statuses,departments);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> sarif(Path file)throws IOException{
        Map<String,Object> root=Json.object(Files.readString(file));List<Map<String,Object>> result=new ArrayList<>();
        for(Object run:list(root.get("runs")))if(run instanceof Map<?,?> r)for(Object finding:list(r.get("results")))
            if(finding instanceof Map<?,?> f){
                Map<String,Object> item=new LinkedHashMap<>();item.put("title",text(f,"ruleId","SARIF finding"));
                item.put("severity",sarifSeverity(text(f,"level","warning")));item.put("description",nestedText(f,"message","text"));
                item.put("component",location(f));result.add(item);
            }
        return result;
    }
    private List<Map<String,Object>> cyclonedx(Path file)throws IOException{
        Map<String,Object> root=Json.object(Files.readString(file));List<Map<String,Object>> result=new ArrayList<>();
        for(Object value:list(root.get("vulnerabilities")))if(value instanceof Map<?,?> vulnerability){
            Map<String,Object> item=new LinkedHashMap<>();item.put("title",text(vulnerability,"id","CycloneDX vulnerability"));
            item.put("cve",text(vulnerability,"id",""));item.put("description",text(vulnerability,"description",""));
            item.put("severity",rating(vulnerability));item.put("component",affect(vulnerability));result.add(item);
        }return result;
    }
    private List<Map<String,Object>> semgrep(Path file)throws IOException{
        Map<String,Object> root=Json.object(Files.readString(file));List<Map<String,Object>> result=new ArrayList<>();
        for(Object value:list(root.get("results")))if(value instanceof Map<?,?> finding){
            Map<String,Object> item=new LinkedHashMap<>();item.put("title",text(finding,"check_id","Semgrep finding"));
            item.put("component",text(finding,"path","source"));item.put("description",nestedText(finding,"extra","message"));
            item.put("severity",nestedText(finding,"extra","severity"));result.add(item);
        }return result;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> generic(Path file)throws IOException{
        Object root=Json.parse(Files.readString(file));
        if(root instanceof List<?> list)return list.stream().filter(Map.class::isInstance)
            .map(v->(Map<String,Object>)new LinkedHashMap<>((Map<String,Object>)v)).toList();
        if(root instanceof Map<?,?> map&&map.get("items") instanceof List<?> list)
            return list.stream().filter(Map.class::isInstance)
                .map(v->(Map<String,Object>)new LinkedHashMap<>((Map<String,Object>)v)).toList();
        if(root instanceof Map<?,?> map)return List.of(new LinkedHashMap<>((Map<String,Object>)map));
        throw new IllegalArgumentException("JSON genérico inválido");
    }
    private void normalizeVulnerability(Map<String,Object> item,String source){
        item.putIfAbsent("title","Finding importado");item.putIfAbsent("component","unknown");
        item.put("severity",String.valueOf(item.getOrDefault("severity","MEDIUM")).toUpperCase(Locale.ROOT));
        item.putIfAbsent("status","OPEN");item.putIfAbsent("source",source);item.putIfAbsent("createdAt",Instant.now().toString());
    }
    private String fingerprint(Map<String,Object> item){return Integer.toHexString(Objects.hash(
        item.get("cve"),item.get("title"),item.get("component"),item.get("dependencyName"),item.get("affectedVersion")));}
    private boolean overdue(Map<String,Object> item){Instant due=instant(item.get("dueAt"));String status=String.valueOf(item.getOrDefault("status","OPEN"));
        return due!=null&&due.isBefore(Instant.now())&&!Set.of("RESOLVED","ACCEPTED").contains(status);}
    private long age(Object value){Instant created=instant(value);return created==null?0:Math.max(0,ChronoUnit.DAYS.between(created,Instant.now()));}
    private Instant instant(Object value){try{return value==null?null:Instant.parse(String.valueOf(value));}catch(Exception e){return null;}}
    private LocalDate date(Object value){try{return value==null?null:LocalDate.parse(String.valueOf(value));}catch(Exception e){return null;}}
    private Double number(Object value){try{return value==null?0:Double.parseDouble(String.valueOf(value));}catch(Exception e){return 0d;}}
    private Map<String,Long> count(List<Map<String,Object>> items,String field){Map<String,Long> result=new TreeMap<>();
        items.forEach(item->result.merge(String.valueOf(item.getOrDefault(field,"UNKNOWN")),1L,Long::sum));return result;}
    @SuppressWarnings("unchecked") private List<Object> list(Object value){return value instanceof List<?> list?new ArrayList<>((List<Object>)list):new ArrayList<>();}
    private String text(Map<?,?> map,String key,String fallback){return map.containsKey(key)?String.valueOf(map.get(key)):fallback;}
    private String nestedText(Map<?,?> map,String object,String key){Object value=map.get(object);return value instanceof Map<?,?> nested?text(nested,key,""):"";}
    private String sarifSeverity(String level){return switch(level.toLowerCase(Locale.ROOT)){case"error"->"HIGH";case"warning"->"MEDIUM";default->"LOW";};}
    private String location(Map<?,?> finding){try{Map<?,?> location=(Map<?,?>)list(finding.get("locations")).getFirst();
        Map<?,?> physical=(Map<?,?>)location.get("physicalLocation");Map<?,?> artifact=(Map<?,?>)physical.get("artifactLocation");
        return text(artifact,"uri","unknown");}catch(Exception e){return"unknown";}}
    private String rating(Map<?,?> vulnerability){try{Map<?,?> rating=(Map<?,?>)list(vulnerability.get("ratings")).getFirst();
        return text(rating,"severity","MEDIUM").toUpperCase(Locale.ROOT);}catch(Exception e){return"MEDIUM";}}
    private String affect(Map<?,?> vulnerability){try{Map<?,?> affect=(Map<?,?>)list(vulnerability.get("affects")).getFirst();
        return text(affect,"ref","unknown");}catch(Exception e){return"unknown";}}

    public record ImportReport(String format,int processed,int created,int updated,int skipped,Path database){}
    public record WorkflowResult(String id,String from,String to,String actor,int historyEntries){}
    public record VulnerabilityReport(int total,long overdue,double averageAgeDays,double mttrHours,
        Map<String,Long> bySeverity,Map<String,Long> byStatus,Map<String,Long> byProject,List<Map<String,Object>> oldest){}
    public record AssetReport(int total,double purchaseValue,long warrantyExpiringIn90Days,long inUseWithoutAssignee,
        Map<String,Long> byType,Map<String,Long> byStatus,Map<String,Long> byDepartment){}
}
