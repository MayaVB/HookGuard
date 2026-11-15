package io.hookguard.endpoint;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    List<Endpoint> findAllByOrderByCreatedAtDesc();
}
