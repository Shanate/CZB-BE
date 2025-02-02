package com.carbonhater.co2zerobookmark.bookmark.service;

import com.carbonhater.co2zerobookmark.bookmark.model.BookmarkCreateDTO;
import com.carbonhater.co2zerobookmark.bookmark.model.dto.*;
import com.carbonhater.co2zerobookmark.bookmark.repository.FolderHistoryRepository;
import com.carbonhater.co2zerobookmark.bookmark.repository.FolderRepository;
import com.carbonhater.co2zerobookmark.bookmark.repository.entity.Bookmark;
import com.carbonhater.co2zerobookmark.bookmark.repository.entity.Folder;
import com.carbonhater.co2zerobookmark.bookmark.repository.entity.FolderHistory;
import com.carbonhater.co2zerobookmark.bookmark.repository.entity.Tag;
import com.carbonhater.co2zerobookmark.common.exception.BadRequestException;
import com.carbonhater.co2zerobookmark.common.exception.NotFoundException;
import com.mysema.commons.lang.Pair;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Stack;
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
                .orElseThrow(() -> new NotFoundException("Folder 에서 ID " + folderId + "를 찾을 수 없습니다."));
    }

    @Transactional
    public void createFolders(FoldersCreateDto foldersCreateDto, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        for (FolderUpdateDto folder : foldersCreateDto.getFolders()) {
            if (Strings.isBlank(folder.getFolderName())) {
                throw new BadRequestException("폴더 이름은 필수입니다.");
            }
            this.saveFolderWithHistory(Folder.builder()
                    .folder(this.getParentFolder(folder.getParentFolderId(), userId))
                    .userId(userId)
                    .tag(tagService.getTag(folder.getTagId()))
                    .folderName(folder.getFolderName())
                    .now(now)
                    .build());
        }
    }

    @Transactional
    public void updateFolder(long folderId, FolderUpdateDto folderUpdateDto, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        Folder folder = getByFolderId(folderId);
        this.validateUserAccess(folder, userId);
        Folder parentFolder = getParentFolder(folderUpdateDto.getParentFolderId(), userId);
        Tag tag = tagService.getTag(folderUpdateDto.getTagId());

        folder.update(parentFolder, tag, folderUpdateDto.getFolderName(), userId, now);
        this.saveFolderWithHistory(folder);
    }

    private Folder getParentFolder(Long parentFolderId, Long userId) {
        if (Objects.isNull(parentFolderId)) {
            return null; // ROOT 폴더인 경우, null 반환
        }

        Folder parentFolderEntity = getByFolderId(parentFolderId);
        this.validateUserAccess(parentFolderEntity, userId);

        return parentFolderEntity;
    }

    @Transactional
    public void deleteFolder(long folderId, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        Folder folder = getByFolderId(folderId);
        this.validateUserAccess(folder, userId);

        // 재귀적으로 하위 폴더 및 관련 북마크 삭제
        deleteFolderAndSubFolders(folder, userId, now);
    }

    @Transactional
    public void deleteFolderAndSubFolders(Folder folder, Long userId, LocalDateTime now) {
        Stack<Folder> stack = new Stack<>();
        stack.push(folder);

        while (!stack.isEmpty()) {
            Folder currentFolder = stack.pop();
            // 하위 폴더들을 스택에 추가
            List<Folder> subFolders = getActiveSubFolders(currentFolder);
            stack.addAll(subFolders);

            // 북마크 삭제 및 현재 폴더 삭제
            bookmarkService.findAllByFolder(currentFolder)
                    .forEach(bookmark -> bookmarkService.deleteBookmark(bookmark.getBookmarkId(), userId));
            currentFolder.delete(userId, now);
            saveFolderWithHistory(currentFolder);
        }
    }

    private List<Folder> getActiveSubFolders(Folder folder) {
        return folderRepository.findActiveByFolder(folder);
    }

    private void validateUserAccess(Folder folder, Long userId) {
        if (!Objects.equals(folder.getUserId(), userId)) {
            throw new BadRequestException("접근 불가능한 폴더입니다.");
        }
    }

    @Transactional
    public Folder saveFolderWithHistory(Folder folder) {
        Folder savedEntity = folderRepository.save(folder);
        folderHistoryRepository.save(FolderHistory.create(savedEntity, LocalDateTime.now()));
        return savedEntity;
    }

    @Transactional
    public void copyParentFolder(Long parentFolderId, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        Folder target = folderRepository.findActiveById(parentFolderId)
                .orElseThrow(() -> new NotFoundException("Folder 에서 ID " + parentFolderId + "를 찾을 수 없습니다."));

        Folder savedParentFolder = this.saveFolderWithHistory(Folder.builder()
                .userId(userId)
                .tag(target.getTag())
                .folderName(target.getFolderName())
                .now(now)
                .build());

        this.copySubFolder(target, savedParentFolder, now, userId);
    }

    @Transactional
    public void copySubFolder(Folder target, Folder savedParentFolder, LocalDateTime now, Long userId) {
        Stack<Pair<Folder, Folder>> stack = new Stack<>();
        stack.push(new Pair<>(target, savedParentFolder));  // (원본 폴더, 새로 생성된 폴더) 쌍을 스택에 푸시

        while (!stack.isEmpty()) {
            Pair<Folder, Folder> current = stack.pop();
            Folder currentFolder = current.getFirst();
            Folder currentSavedFolder = current.getSecond();

            // 하위 폴더 생성
            List<Folder> subFolders = this.getActiveSubFolders(currentFolder);
            for (Folder subFolder : subFolders) {
                Folder savedSubFolder = this.saveFolderWithHistory(Folder.builder()
                        .folder(currentSavedFolder)
                        .userId(userId)
                        .tag(subFolder.getTag())
                        .folderName(subFolder.getFolderName())
                        .now(now)
                        .build());

                // 북마크 생성
                for (Bookmark bookmark : bookmarkService.findAllByFolder(subFolder)) {
                    BookmarkCreateDTO dto = new BookmarkCreateDTO();
                    dto.setBookmarkName(bookmark.getBookmarkName());
                    dto.setBookmarkUrl(bookmark.getBookmarkUrl());
                    dto.setFolderId(savedSubFolder.getFolderId());
                    bookmarkService.createBookmark(dto, userId);
                }

                // 하위 폴더도 스택에 푸시
                stack.push(new Pair<>(subFolder, savedSubFolder));
            }
        }
    }

    public FolderHierarchyDto getFolderHierarchyByParentFolderId(Long parentFolderId) {
        // 루트 폴더들을 가져옴 (parent folder가 null인 폴더들)
        Folder parentFolder = folderRepository.findActiveById(parentFolderId)
                .orElseThrow(() -> new NotFoundException("Folder 에서 ID " + parentFolderId + "를 찾을 수 없습니다."));

        // 폴더 계층 구조를 DTO로 변환
        return new FolderHierarchyDto(this.mapFolderToDto(parentFolder));
    }

    private FolderHierarchyDto.FolderDto mapFolderToDto(Folder folder) {
        // 최상위 폴더 생성
        FolderHierarchyDto.FolderDto rootFolderDto = new FolderHierarchyDto.FolderDto(folder);

        // 스택을 사용하여 폴더 구조를 처리
        Stack<Pair<Folder, FolderHierarchyDto.FolderDto>> stack = new Stack<>();
        stack.push(new Pair<>(folder, rootFolderDto));

        while (!stack.isEmpty()) {
            Pair<Folder, FolderHierarchyDto.FolderDto> current = stack.pop();
            Folder currentFolder = current.getFirst();
            FolderHierarchyDto.FolderDto currentFolderDto = current.getSecond();

            // 현재 폴더의 북마크를 DTO로 변환하여 추가
            List<Bookmark> bookmarks = bookmarkService.findAllByFolder(currentFolder);
            currentFolderDto.setBookmarks(bookmarks.stream()
                    .map(FolderHierarchyDto.BookmarkDto::new)
                    .collect(Collectors.toList()));

            // 현재 폴더의 하위 폴더들을 가져옴
            List<Folder> subFolders = this.getActiveSubFolders(currentFolder);

            // 하위 폴더를 순차적으로 처리
            for (Folder subFolder : subFolders) {
                FolderHierarchyDto.FolderDto subFolderDto = new FolderHierarchyDto.FolderDto(subFolder);
                currentFolderDto.getSubFolders().add(subFolderDto);
                stack.push(new Pair<>(subFolder, subFolderDto));
            }
        }

        return rootFolderDto;
    }

    public List<FolderDto> getRootFoldersByUserId(Long userId) {
        return folderRepository.findByUserIdAndFolderIsNull(userId)
                .stream().map(FolderDto::new).toList();
    }
}
