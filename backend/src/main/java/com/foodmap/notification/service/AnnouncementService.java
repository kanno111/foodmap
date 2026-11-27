package com.foodmap.notification.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.common.exception.CommonErrorCode;
import com.foodmap.notification.dto.AnnouncementPublishRequest;
import com.foodmap.notification.dto.AnnouncementQuery;
import com.foodmap.notification.entity.AnnouncementEntity;
import com.foodmap.notification.exception.NotificationErrorCode;
import com.foodmap.notification.mapper.AnnouncementMapper;
import com.foodmap.notification.vo.AnnouncementViews;
import com.foodmap.security.service.CurrentActorService;
import com.foodmap.user.service.UserDirectoryService;
import com.foodmap.user.domain.ManualRewardType;
import com.foodmap.user.service.UserManualRewardService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 公告发布、撤回和可见性；仅指定公告保存收件人，正文始终保存一份。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AnnouncementService {
    private static final int RECIPIENT_PAGE_SIZE = 10;
    private final AnnouncementMapper announcements;
    private final CurrentActorService actors;
    private final NotificationChangeService changes;
    private final UserDirectoryService users;
    private final UserManualRewardService rewards;

    /** 只为管理端奖励历史补充来源标题和撤回状态。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Map<Long, AnnouncementViews.RewardSource> rewardSources(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return announcements.rewardSources(ids).stream()
                .collect(Collectors.toMap(AnnouncementViews.RewardSource::id, source -> source));
    }

    /** 使用发布人+UUID去重；唯一键竞争结束后核验摘要，失败整笔回滚。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AnnouncementViews.Detail publish(AnnouncementPublishRequest request, boolean targeted) {
        long owner = admin();
        if (request == null || request.getClientRequestId() == null) {
            throw invalid();
        }
        String title = normalize(request.getTitle(), 120),
                content = normalize(request.getContent(), 10000);
        var uuid = request.getClientRequestId();
        byte[] key =
                ByteBuffer.allocate(16)
                        .putLong(uuid.getMostSignificantBits())
                        .putLong(uuid.getLeastSignificantBits())
                        .array();
        List<Long> recipients = recipientIds(request.getRecipientUserIds(), targeted);
        var reward = request.getRewardRegistration();
        String rewardNote = rewardNote(reward, targeted);
        byte[] hash = hash(title, content, recipients, reward, rewardNote);
        var row = new AnnouncementEntity();
        row.setClientRequestId(key);
        row.setRequestHash(hash);
        row.setTitle(title);
        row.setContent(content);
        row.setPublishedBy(owner);
        row.setAudience(targeted ? "TARGETED" : "ALL");
        row.setStatus(targeted ? "TARGETED_PUBLISHED" : "PUBLISHED");
        try {
            announcements.insert(row);
        } catch (DuplicateKeyException duplicate) {
            var existing = announcements.byRequest(owner, key);
            if (existing == null) {
                throw duplicate;
            }
            if (!MessageDigest.isEqual(existing.getRequestHash(), hash)) {
                throw new BusinessException(CommonErrorCode.DATA_CONFLICT);
            }
            return view(existing, true);
        }
        if (targeted) {
            if (users.lockActiveIds(recipients).size() != recipients.size()) {
                throw new BusinessException(
                        CommonErrorCode.VALIDATION_FAILED, "有收件人已不可用，请重新选择。");
            }
            announcements.insertRecipients(row.getId(), recipients);
            if (reward != null) {
                rewards.register(recipients, reward.getType(), rewardNote, row.getId(), owner);
            }
            recipients.forEach(changes::changed);
        } else {
            changes.allChanged();
        }
        return view(announcements.find(row.getId()), false);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public AnnouncementViews.RecipientPage searchRecipients(String keyword, int page) {
        admin();
        long offset = ((long) page - 1) * RECIPIENT_PAGE_SIZE;
        var matches = users.searchActive(keyword, offset, RECIPIENT_PAGE_SIZE + 1);
        var items = matches.stream()
                .limit(RECIPIENT_PAGE_SIZE)
                .map(user -> new AnnouncementViews.Recipient(
                        Long.toString(user.getId()), user.getUsername()))
                .toList();
        return new AnnouncementViews.RecipientPage(items, matches.size() > RECIPIENT_PAGE_SIZE);
    }

    /** 管理端可核对撤回记录，用户侧始终通过独立可见性查询。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnnouncementViews.Page page(AnnouncementQuery query) {
        admin();
        var result = new AnnouncementViews.Page();
        result.setTotal(announcements.count(query.getStatus(), query.getAudience()));
        result.setPage(query.getPage());
        result.setPageSize(query.getPageSize());
        result.setTotalPages((result.getTotal() + query.getPageSize() - 1) / query.getPageSize());
        var rows = announcements.page(
                query.getStatus(),
                query.getAudience(),
                ((long) query.getPage() - 1) * query.getPageSize(),
                query.getPageSize());
        result.setRecords(adminRecords(rows));
        return result;
    }

    private List<AnnouncementViews.AdminRecord> adminRecords(List<AnnouncementEntity> rows) {
        var targetedIds = rows.stream()
                .filter(row -> "TARGETED".equals(row.getAudience()))
                .map(AnnouncementEntity::getId)
                .toList();
        var previews = targetedIds.isEmpty() ? List.<AnnouncementMapper.RecipientPreviewRow>of()
                : announcements.recipientPreviews(targetedIds);
        var byAnnouncement = previews.stream().collect(
                Collectors.groupingBy(AnnouncementMapper.RecipientPreviewRow::announcementId));
        var names = users.namesByIds(previews.stream()
                .map(AnnouncementMapper.RecipientPreviewRow::userId)
                .distinct()
                .toList());
        return rows.stream()
                .map(row -> adminRecord(row, byAnnouncement.getOrDefault(row.getId(), List.of()), names))
                .toList();
    }

    private AnnouncementViews.AdminRecord adminRecord(
            AnnouncementEntity row,
            List<AnnouncementMapper.RecipientPreviewRow> preview,
            Map<Long, String> names) {
        var record = view(row, false, new AnnouncementViews.AdminRecord());
        record.setRecipientCount(preview.isEmpty() ? 0 : preview.getFirst().recipientCount());
        record.setRecipientPreview(preview.stream()
                .map(recipient -> recipient(recipient.userId(), names))
                .toList());
        return record;
    }

    private AnnouncementViews.Recipient recipient(long userId, Map<Long, String> names) {
        return new AnnouncementViews.Recipient(Long.toString(userId), names.get(userId));
    }

    /** 管理员读取完整历史，不提供正文修改接口。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public AnnouncementViews.AdminDetail adminDetail(String id) {
        admin();
        var row = announcements.find(key(id));
        if (row == null) {
            throw missing();
        }
        var result = view(row, false, new AnnouncementViews.AdminDetail());
        List<Long> recipients = "TARGETED".equals(row.getAudience())
                ? announcements.recipients(row.getId()) : List.of();
        var names = users.namesByIds(recipients);
        result.setRecipients(recipients.stream()
                .map(userId -> recipient(userId, names))
                .toList());
        return result;
    }

    /** 首次撤回人及时间永久保留；其他管理员重复撤回也返回同一终态。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AnnouncementViews.Detail withdraw(String id) {
        long owner = admin();
        long key = key(id);
        var row = announcements.lock(key);
        if (row == null) {
            throw missing();
        }
        if (!isPublished(row)) {
            return view(row, true);
        }
        announcements.withdraw(key, owner);
        if ("ALL".equals(row.getAudience())) {
            changes.allChanged();
        } else {
            announcements.recipients(key).forEach(changes::changed);
        }
        return view(announcements.find(key), false);
    }

    /** GET不标记已读；删除或撤回后不再暴露公告正文。 */
    @Transactional(readOnly = true)
    public AnnouncementViews.Detail detail(String id) {
        long owner = actors.requireActor().getUserId();
        var row = announcements.visible(owner, key(id));
        if (row == null) {
            throw missing();
        }
        return view(row, false);
    }

    /** 单条已读与撤回共用主行锁，保留首次已读；用户不可指定他人身份。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void read(String id) {
        long owner = actors.requireActor().getUserId(), key = key(id);
        var row = announcements.lock(key);
        requireRecipient(row, owner);
        requirePublished(row);
        if (announcements.deleted(owner, key) != null) {
            throw missing();
        }
        announcements.read(owner, key);
        changes.changed(owner);
    }

    /** 本人重复删除成功；删除回执在公告撤回后仍可安全重试，不重建可见内容。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(String id) {
        long owner = actors.requireActor().getUserId(), key = key(id);
        var row = announcements.lock(key);
        requireRecipient(row, owner);
        if (announcements.deleted(owner, key) != null) {
            return;
        }
        requirePublished(row);
        announcements.delete(owner, key);
        changes.changed(owner);
    }

    /** 参与收件箱RR事务，按初始快照分页取ID，再按升序锁行复核撤回和删除。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void readAll(long owner) {
        if (actors.requireActor().getUserId() != owner) {
            throw new AccessDeniedException("仅能处理本人公告");
        }
        long after = 0;
        while (true) {
            var ids = announcements.unreadIds(owner, after);
            if (ids.isEmpty()) {
                break;
            }
            for (long id : ids) {
                var row = announcements.lock(id);
                if (row != null
                        && isPublished(row)
                        && announcements.deleted(owner, id) == null) {
                    announcements.read(owner, id);
                    changes.changed(owner);
                }
            }
            after = ids.getLast();
        }
    }

    private long admin() {
        var actor = actors.requireActor();
        if (!actor.isAdministrator()) {
            throw new AccessDeniedException("需要管理员权限");
        }
        return actor.getUserId();
    }

    private void requirePublished(AnnouncementEntity row) {
        if (!isPublished(row)) {
            throw missing();
        }
    }

    private boolean isPublished(AnnouncementEntity row) {
        return "PUBLISHED".equals(row.getStatus()) || "TARGETED_PUBLISHED".equals(row.getStatus());
    }

    private void requireRecipient(AnnouncementEntity row, long owner) {
        if (row == null
                || (!"ALL".equals(row.getAudience()) && !announcements.isRecipient(row.getId(), owner))) {
            throw missing();
        }
    }

    private BusinessException missing() {
        return new BusinessException(NotificationErrorCode.NOT_FOUND);
    }

    private BusinessException invalid() {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED);
    }

    private long key(String id) {
        if (id == null
                || !id.matches("[1-9][0-9]{0,18}")
                || new java.math.BigInteger(id).bitLength() > 63) {
            throw invalid();
        }
        return Long.parseLong(id);
    }

    private String normalize(String value, int limit) {
        if (value == null) {
            throw invalid();
        }
        String result = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (result.isBlank() || result.codePointCount(0, result.length()) > limit) {
            throw invalid();
        }
        return result;
    }

    private List<Long> recipientIds(List<String> ids, boolean targeted) {
        if (!targeted && ids == null) {
            return List.of();
        }
        if (!targeted || ids == null || ids.isEmpty() || ids.size() > 100) {
            throw invalid();
        }
        return ids.stream().map(this::key).distinct().sorted().toList();
    }

    private String rewardNote(AnnouncementPublishRequest.RewardRegistration reward, boolean targeted) {
        if (reward == null) {
            return null;
        }
        if (!targeted || reward.getType() == null) {
            throw invalid();
        }
        String note = reward.getNote();
        if (note == null || note.isBlank()) {
            if (reward.getType() == ManualRewardType.OTHER) {
                throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, "选择其他奖励时必须填写说明。");
            }
            return null;
        }
        return normalize(note, 300);
    }

    private byte[] hash(String title, String content, List<Long> recipients,
            AnnouncementPublishRequest.RewardRegistration reward, String rewardNote) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] first = title.getBytes(StandardCharsets.UTF_8);
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            // 全体公告沿用既有摘要。指定公告使用独立前缀与长度边界，收件人顺序不影响重试。
            if (reward != null) {
                // 奖励登记使用独立摘要格式，已发布的普通公告保留既有重试协议。
                digest.update(ByteBuffer.allocate(4).putInt(-2).array());
                var type = reward.getType().name().getBytes(StandardCharsets.UTF_8);
                var note = (rewardNote == null ? "" : rewardNote).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(type.length).array());
                digest.update(type);
                digest.update(ByteBuffer.allocate(4).putInt(note.length).array());
                digest.update(note);
            } else if (!recipients.isEmpty()) {
                digest.update(ByteBuffer.allocate(4).putInt(-1).array());
            }
            digest.update(ByteBuffer.allocate(4).putInt(first.length).array());
            digest.update(first);
            if (!recipients.isEmpty()) {
                digest.update(ByteBuffer.allocate(4).putInt(body.length).array());
            }
            digest.update(body);
            for (long id : recipients) {
                digest.update(ByteBuffer.allocate(8).putLong(id).array());
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private AnnouncementViews.Detail view(AnnouncementEntity row, boolean replayed) {
        return view(row, replayed, new AnnouncementViews.Detail());
    }

    private <T extends AnnouncementViews.Detail> T view(
            AnnouncementEntity row, boolean replayed, T result) {
        result.setId(row.getId().toString());
        result.setTitle(row.getTitle());
        result.setContent(row.getContent());
        result.setStatus(isPublished(row) ? "PUBLISHED" : "WITHDRAWN");
        result.setAudience(row.getAudience());
        result.setPublishedBy(row.getPublishedBy().toString());
        result.setPublishedAt(row.getPublishedAt());
        result.setWithdrawnBy(
                row.getWithdrawnBy() == null ? null : row.getWithdrawnBy().toString());
        result.setWithdrawnAt(row.getWithdrawnAt());
        result.setReplayed(replayed);
        return result;
    }
}
