package dev.swissknife.itamboot.domain;

import dev.swissknife.itamboot.api.AssetRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
@Transactional
public class AssetService {
    private final AssetRepository repository;
    public AssetService(AssetRepository repository){this.repository=repository;}
    /** Teto de registros por requisição: sem ele GET /assets materializa a tabela inteira (ver VulnerabilityService). */
    static final int MAX_PAGE_SIZE=500;
    static final int DEFAULT_PAGE_SIZE=200;

    @Transactional(readOnly=true) public List<Asset> list(){return list(null,null,null,null);}
    /**
     * Filtragem via Specification (traduzida para WHERE no banco) em vez de findAll()+stream, para
     * não carregar a tabela inteira em memória a cada chamada quando o volume de ativos crescer.
     */
    @Transactional(readOnly=true)
    public List<Asset> list(Asset.Status status,Asset.Type type,String owner,String query){
        return list(status,type,owner,query,0,DEFAULT_PAGE_SIZE);
    }

    @Transactional(readOnly=true)
    public List<Asset> list(Asset.Status status,Asset.Type type,String owner,String query,int page,int size){
        Specification<Asset> spec=Specification.allOf();
        if(status!=null) spec=spec.and((root,cq,cb)->cb.equal(root.get("status"),status));
        if(type!=null) spec=spec.and((root,cq,cb)->cb.equal(root.get("type"),type));
        if(owner!=null && !owner.isBlank()) spec=spec.and((root,cq,cb)->cb.equal(cb.lower(root.get("assignedTo")),owner.toLowerCase(Locale.ROOT)));
        if(query!=null && !query.isBlank()){
            String pattern="%"+query.toLowerCase(Locale.ROOT)+"%";
            spec=spec.and((root,cq,cb)->cb.or(
                cb.like(cb.lower(root.get("tag")),pattern), cb.like(cb.lower(root.get("name")),pattern),
                cb.like(cb.lower(cb.coalesce(root.get("serialNumber"),"")),pattern),
                cb.like(cb.lower(cb.coalesce(root.get("hostname"),"")),pattern),
                cb.like(cb.lower(cb.coalesce(root.get("manufacturer"),"")),pattern),
                cb.like(cb.lower(cb.coalesce(root.get("model"),"")),pattern)));
        }
        var pageable=PageRequest.of(Math.max(0,page),Math.min(Math.max(1,size),MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC,"createdAt"));
        return repository.findAll(spec,pageable).getContent();
    }
    @Transactional(readOnly=true) public Asset get(UUID id){return repository.findById(id).orElseThrow(()->new ResponseStatusException(NOT_FOUND));}
    public Asset create(AssetRequest r){
        if(repository.existsByTag(r.tag()))throw new ResponseStatusException(CONFLICT,"Tag já cadastrada");
        return repository.save(new Asset(r.tag(),r.name(),r.type(),r.serialNumber(),r.assignedTo(),r.purchaseValue(),
        r.purchaseDate(),r.manufacturer(),r.model(),r.hostname(),r.ipAddress(),r.macAddress(),r.operatingSystem(),
        r.osVersion(),r.locationName(),r.department(),r.costCenter(),r.vendor(),r.warrantyEnd(),r.notes(),r.tagsText()));
    }
    public Asset update(UUID id,AssetRequest r){
        var a=get(id);a.update(r.tag(),r.name(),r.type(),r.status()==null?a.getStatus():r.status(),r.serialNumber(),
        r.assignedTo(),r.purchaseValue(),r.purchaseDate(),r.manufacturer(),r.model(),r.hostname(),r.ipAddress(),
        r.macAddress(),r.operatingSystem(),r.osVersion(),r.locationName(),r.department(),r.costCenter(),r.vendor(),
        r.warrantyEnd(),r.notes(),r.tagsText());return a;
    }
    public void delete(UUID id){if(!repository.existsById(id))throw new ResponseStatusException(NOT_FOUND);repository.deleteById(id);}
    public Asset transition(UUID id,Asset.Status status,String owner){var a=get(id);a.transition(status,owner);return a;}
    @Transactional(readOnly=true) public Map<String,Object> inventory(){
        var all=repository.findAll();Map<String,Long> types=new TreeMap<>(),statuses=new TreeMap<>();
        all.forEach(a->{types.merge(a.getType().name(),1L,Long::sum);statuses.merge(a.getStatus().name(),1L,Long::sum);});
        var total=all.stream().map(Asset::getPurchaseValue).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
        long warrantyExpiring=all.stream().filter(Asset::isWarrantyExpiring).count();
        return Map.of("total",all.size(),"totalPurchaseValue",total,"byType",types,"byStatus",statuses,
            "warrantyExpiringIn90Days",warrantyExpiring);
    }
}
