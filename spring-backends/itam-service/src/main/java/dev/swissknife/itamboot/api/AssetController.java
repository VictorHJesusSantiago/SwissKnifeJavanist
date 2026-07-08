package dev.swissknife.itamboot.api;

import dev.swissknife.itamboot.domain.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class AssetController {
    private final AssetService service;
    public AssetController(AssetService service){this.service=service;}
    @GetMapping("/assets") public List<Asset> list(@RequestParam(required=false) Asset.Status status,
        @RequestParam(required=false) Asset.Type type,@RequestParam(required=false) String assignedTo,
        @RequestParam(required=false,name="q") String query){return service.list(status,type,assignedTo,query);}
    @GetMapping("/assets/{id}") public Asset get(@PathVariable UUID id){return service.get(id);}
    @PostMapping("/assets") @ResponseStatus(HttpStatus.CREATED) public Asset create(@Valid @RequestBody AssetRequest body){return service.create(body);}
    @PutMapping("/assets/{id}") public Asset update(@PathVariable UUID id,@Valid @RequestBody AssetRequest body){return service.update(id,body);}
    @PatchMapping("/assets/{id}/status") public Asset transition(@PathVariable UUID id,
        @Valid @RequestBody StatusTransition body){return service.transition(id,body.status(),body.assignedTo());}
    @DeleteMapping("/assets/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id){service.delete(id);}
    @GetMapping("/inventory") public Map<String,Object> inventory(){return service.inventory();}
    public record StatusTransition(@jakarta.validation.constraints.NotNull Asset.Status status,String assignedTo){}
}
