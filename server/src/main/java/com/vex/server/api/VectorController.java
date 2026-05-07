package com.vex.server.api;

import com.vex.server.api.dto.Dtos.BatchUpsertRequest;
import com.vex.server.api.dto.Dtos.QueryRequest;
import com.vex.server.api.dto.Dtos.QueryResponseItem;
import com.vex.server.api.dto.Dtos.UpsertRequest;
import com.vex.server.api.dto.Dtos.VectorResponse;
import com.vex.server.domain.Collection;
import com.vex.server.domain.CollectionManager;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/collections/{name}")
public class VectorController {

  private final CollectionManager manager;

  public VectorController(CollectionManager manager) {
    this.manager = manager;
  }

  @PostMapping("/upsert")
  public ResponseEntity<Void> upsert(
      @PathVariable String name, @Valid @RequestBody UpsertRequest req) throws IOException {
    Collection c = manager.require(name);
    Map<String, Object> payload = req.payload();
    c.upsert(req.id(), req.vector(), payload);
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/upsert/batch")
  public ResponseEntity<Void> batch(
      @PathVariable String name, @Valid @RequestBody BatchUpsertRequest req) throws IOException {
    Collection c = manager.require(name);
    for (UpsertRequest r : req.vectors()) {
      c.upsert(r.id(), r.vector(), r.payload());
    }
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/query")
  public List<QueryResponseItem> query(
      @PathVariable String name, @Valid @RequestBody QueryRequest req) {
    Collection c = manager.require(name);
    var hits = c.query(req.vector(), req.k(), req.efSearch(), req.filter());
    List<QueryResponseItem> out = new ArrayList<>(hits.size());
    for (Collection.QueryHit h : hits) {
      out.add(new QueryResponseItem(h.id(), h.distance(), h.payload()));
    }
    return out;
  }

  @GetMapping("/vectors/{id}")
  public ResponseEntity<VectorResponse> getVector(
      @PathVariable String name, @PathVariable long id) {
    Collection c = manager.require(name);
    float[] v = c.getVector(id);
    if (v == null) {
      return ResponseEntity.notFound().build();
    }
    Map<String, Object> p = c.getPayload(id);
    return ResponseEntity.ok(new VectorResponse(id, v, p == null ? Map.of() : p));
  }

  @DeleteMapping("/vectors/{id}")
  public ResponseEntity<Void> deleteVector(@PathVariable String name, @PathVariable long id)
      throws IOException {
    Collection c = manager.require(name);
    boolean ok = c.delete(id);
    return ok ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }
}
