package com.carbonhater.co2zerobookmark.bookmark.service;

import com.carbonhater.co2zerobookmark.bookmark.model.dto.FolderHierarchyDto;
import com.carbonhater.co2zerobookmark.bookmark.model.dto.FolderUpdateDto;
import com.carbonhater.co2zerobookmark.bookmark.model.dto.FoldersCreateDto;
import com.carbonhater.co2zerobookmark.bookmark.repository.FolderHistoryRepository;
import com.carbonhater.co2zerobookmark.bookmark.repository.FolderRepository;
import com.carbonhater.co2zerobookmark.bookmark.repository.entity.Bookmark;
import com.carbonhater.co2zerobookmark.bookmark.repository.entity.Folder;
import com.carbonhater.co2zerobookmark.bookmark.repository.entity.FolderHistory;
import com.carbonhater.co2zerobookmark.bookmark.repository.entity.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FolderService {

    private final FolderRepository folderRepository;
    private final FolderHistoryRepository folderHistoryRepository;
    private final TagService tagService;
    private final BookmarkService bookmarkService;

    public Folder getByFolderId(Long folderId) {
        return folderRepository.findActiveById(folderId)
                .orElseThrow(() -> new RuntimeException("Folder 에서 ID " + folderId + "를 찾을 수 없습니다.")); //TODO Exception Handler
    }

    @Transactional
    public void createFolders(FoldersCreateDto foldersCreateDto, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        List<Folder> folders = new ArrayList<>();
        List<FolderHistory> histories = new ArrayList<>();
        for (FolderUpdateDto folder : foldersCreateDto.getFolders()) {
            if (Strings.isBlank(folder.getFolderName())) {
                throw new RuntimeException("폴더 이름은 필수입니다."); //TODO Exception Handler
            }
            Folder folderEntity = Folder.builder()
                    .folder(this.getParentFolder(folder.getParentFolderId(), userId))
                    .userId(userId)
                    .tag(tagService.getTag(folder.getTagId()))
                    .folderName(folder.getFolderName())
                    .now(now)
                    .build();
            folders.add(folderEntity);
            histories.add(FolderHistory.create(folderEntity, now));
        }

        folderRepository.saveAll(folders);
        folderHistoryRepository.saveAll(histories);
    }

    @Transactional
    public void updateFolder(long folderId, FolderUpdateDto folderUpdateDto, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        Folder folder = getByFolderId(folderId);
        validateUserAccess(folder, userId);
        Folder parentFolder = getParentFolder(folderUpdateDto.getParentFolderId(), userId);
        Tag tag = tagService.getTag(folderUpdateDto.getTagId());

        folder.update(parentFolder, tag, folderUpdateDto.getFolderName(), userId, now);
        folderRepository.save(folder);
        folderHistoryRepository.save(FolderHistory.create(folder, now));
    }

    private Folder getParentFolder(Long parentFolderId, Long userId) {
        if (Objects.isNull(parentFolderId)) {
            return null; // ROOT 폴더인 경우, null 반환
        }

        Folder parentFolderEntity = getByFolderId(parentFolderId);
        validateUserAccess(parentFolderEntity, userId);

        return parentFolderEntity;
    }

    @Transactional
    public void deleteFolder(long folderId, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        Folder folder = getByFolderId(folderId);
        validateUserAccess(folder, userId);

        // 재귀적으로 하위 폴더 및 관련 북마크 삭제
        deleteFolderAndSubFolders(folder, userId, now);
    }

    private void deleteFolderAndSubFolders(Folder folder, Long userId, LocalDateTime now) {
        // 하위 폴더 삭제 (재귀 호출)
        for (Folder subFolder : this.getActiveSubFoldersByUserId(folder, userId)) {
            deleteFolderAndSubFolders(subFolder, userId, now);
        }

        // 현재 폴더 삭제 처리
        folder.delete(userId, now);
        folderRepository.save(folder);
        folderHistoryRepository.save(FolderHistory.create(folder, now));

        // 폴더에 포함된 북마크 삭제
        bookmarkService.findAllByFolder(folder).stream()
                .map(Bookmark::getBookmarkId)
                .forEach(bookmarkService::deleteBookmark);
    }

    private List<Folder> getActiveSubFoldersByUserId(Folder folder, Long userId) {
        return folderRepository.findByFolderAndUserId(folder, userId);
    }

    private void validateUserAccess(Folder folder, Long userId) {
        if (!Objects.equals(folder.getUserId(), userId)) {
            throw new RuntimeException("접근 불가능한 폴더입니다."); // TODO Exception Handler
        }
    }

    public FolderHierarchyDto getFolderHierarchyByUserId(Long userId) {
        // 루트 폴더들을 가져옴 (parent folder가 null인 폴더들)
        List<Folder> rootFolders = folderRepository.findByUserIdAndFolderIsNull(userId);

        // 폴더 계층 구조를 DTO로 변환
        List<FolderHierarchyDto.FolderDto> folders = rootFolders.stream()
                .map(folder -> mapFolderToDto(folder, userId))
                .collect(Collectors.toList());

        return new FolderHierarchyDto(folders);
    }

    private FolderHierarchyDto.FolderDto mapFolderToDto(Folder folder, Long userId) {
        // 하위 폴더 가져오기
        List<Folder> subFolders = this.getActiveSubFoldersByUserId(folder, userId);

        // 북마크 가져오기
        List<Bookmark> bookmarks = bookmarkService.findAllByFolder(folder);

        // Folder 변환
        FolderHierarchyDto.FolderDto folderDto = new FolderHierarchyDto.FolderDto();
        folderDto.setFolderId(folder.getFolderId());
        folderDto.setFolderName(folder.getFolderName());

        // Tag 변환
        if (folder.getTag() != null) {
            folderDto.setTag(new FolderHierarchyDto.TagDto(folder.getTag()));
        }

        // 하위 폴더와 북마크 재귀적으로 DTO로 변환
        folderDto.setSubFolders(subFolders.stream()
                .map(subFolder -> mapFolderToDto(subFolder, userId))
                .collect(Collectors.toList()));

        folderDto.setBookmarks(bookmarks.stream()
                .map(FolderHierarchyDto.BookmarkDto::new)
                .collect(Collectors.toList()));

        return folderDto;
    }
}
