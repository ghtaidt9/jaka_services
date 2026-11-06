package com.jaka.order_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jaka.order_service.entity.ProjectedEvent;

public interface ProjectedEventRepository extends JpaRepository<ProjectedEvent, String> {

}
