package com.carbonhater.co2zerobookmark.bookmark.repository;

import com.carbonhater.co2zerobookmark.bookmark.repository.entity.Folder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    @Query("select f from Folder f where f.deletedYn = 'N' and f.folderId = :id")
    Optional<Folder> findActiveById(Long id);

    @Query("select f from Folder f where f.deletedYn = 'N' and f.folder.folderId = :parentFolderId")
    List<Folder> findActiveSubFolders(Long parentFolderId);
}
