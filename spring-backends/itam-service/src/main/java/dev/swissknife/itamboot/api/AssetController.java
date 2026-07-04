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
    @GetMapping("/assets") public List<Asset> list(){return service.list();}
    @GetMapping("/assets/{id}") public Asset get(@PathVariable UUID id){return service.get(id);}
    @PostMapping("/assets") @ResponseStatus(HttpStatus.CREATED) public Asset create(@Valid @RequestBody AssetRequest body){return service.create(body);}
    @PutMapping("/assets/{id}") public Asset update(@PathVariable UUID id,@Valid @RequestBody AssetRequest body){return service.update(id,body);}
    @DeleteMapping("/assets/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id){service.delete(id);}
    @GetMapping("/inventory") public Map<String,Object> inventory(){return service.inventory();}
}
