package dev.swissknife.itamboot.domain;

import dev.swissknife.itamboot.api.AssetRequest;
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
    @Transactional(readOnly=true) public List<Asset> list(){return list(null,null,null,null);}
    @Transactional(readOnly=true)
    public List<Asset> list(Asset.Status status,Asset.Type type,String owner,String query){
        String q=query==null?"":query.toLowerCase(Locale.ROOT);
        return repository.findAll().stream().filter(a->status==null||a.getStatus()==status)
            .filter(a->type==null||a.getType()==type)
            .filter(a->owner==null||owner.equalsIgnoreCase(Objects.toString(a.getAssignedTo(),"")))
            .filter(a->q.isBlank()||searchable(a).contains(q))
            .sorted(Comparator.comparing(Asset::getCreatedAt).reversed()).toList();
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
    private String searchable(Asset a){return String.join(" ",Objects.toString(a.getTag(),""),
        Objects.toString(a.getName(),""),Objects.toString(a.getSerialNumber(),""),
        Objects.toString(a.getHostname(),""),Objects.toString(a.getManufacturer(),""),
        Objects.toString(a.getModel(),"")).toLowerCase(Locale.ROOT);}
}
