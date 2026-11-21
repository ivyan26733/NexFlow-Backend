package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    List<Announcement> findByPublishedTrue();
}
