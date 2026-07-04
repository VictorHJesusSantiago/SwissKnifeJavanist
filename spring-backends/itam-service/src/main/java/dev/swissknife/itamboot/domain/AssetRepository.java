package dev.swissknife.itamboot.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {
    boolean existsByTag(String tag);
}
