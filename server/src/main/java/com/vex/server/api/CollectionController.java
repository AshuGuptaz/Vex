package com.vex.server.api;

import com.vex.server.api.dto.Dtos;
import com.vex.server.api.dto.Dtos.CollectionInfoResponse;
import com.vex.server.api.dto.Dtos.CreateCollectionRequest;
import com.vex.server.domain.Collection;
import com.vex.server.domain.CollectionManager;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/collections")
public class CollectionController {

  private final CollectionManager manager;

  public CollectionController(CollectionManager manager) {
    this.manager = manager;
  }

  @PostMapping
  public ResponseEntity<CollectionInfoResponse> create(
      @Valid @RequestBody CreateCollectionRequest req) throws IOException {
    int M = req.M() != null ? req.M() : 16;
    int efC = req.efConstruction() != null ? req.efConstruction() : 200;
    boolean quantized = "scalar".equalsIgnoreCase(req.quantization());
    Collection c = manager.create(req.name(), req.dim(), req.metric(), M, efC, quantized);
    return ResponseEntity.status(HttpStatus.CREATED).body(toInfo(c));
  }

  @GetMapping
  public List<String> list() {
    return manager.names();
  }

  @GetMapping("/{name}")
  public CollectionInfoResponse info(@PathVariable String name) {
    return toInfo(manager.require(name));
  }

  @DeleteMapping("/{name}")
  public ResponseEntity<Void> drop(@PathVariable String name) throws IOException {
    boolean ok = manager.drop(name);
    return ok ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }

  private static CollectionInfoResponse toInfo(Collection c) {
    var cfg = c.config();
    return new Dtos.CollectionInfoResponse(
        c.name(),
        cfg.dimension(),
        cfg.metric().name(),
        cfg.M(),
        cfg.efConstruction(),
        c.size(),
        c.quantized());
  }
}
