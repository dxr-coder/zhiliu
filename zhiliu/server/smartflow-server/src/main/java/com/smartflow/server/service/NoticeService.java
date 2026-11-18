package com.smartflow.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.pojo.entity.Notice;
import com.smartflow.server.mapper.NoticeMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NoticeService {

    private final NoticeMapper noticeMapper;

    public NoticeService(NoticeMapper noticeMapper) {
        this.noticeMapper = noticeMapper;
    }

    public void push(Long userId, String type, String title, String body,
                     String bizType, Long bizId) {
        Notice n = new Notice();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setBizType(bizType);
        n.setBizId(bizId);
        n.setReadFlag(false);
        n.setCreatedAt(LocalDateTime.now());
        noticeMapper.insert(n);
    }

    public List<Notice> list(Long userId, Boolean unreadOnly, String type) {
        return noticeMapper.selectList(new LambdaQueryWrapper<Notice>()
                .eq(Notice::getUserId, userId)
                .eq(Boolean.TRUE.equals(unreadOnly), Notice::getReadFlag, false)
                .eq(type != null && !type.isBlank(), Notice::getType, type)
                .orderByDesc(Notice::getCreatedAt)
                .last("LIMIT 100"));
    }

    public long unreadCount(Long userId) {
        Long c = noticeMapper.selectCount(new LambdaQueryWrapper<Notice>()
                .eq(Notice::getUserId, userId)
                .eq(Notice::getReadFlag, false));
        return c == null ? 0 : c;
    }

    public void readAll(Long userId) {
        Notice upd = new Notice();
        upd.setReadFlag(true);
        noticeMapper.update(upd, new LambdaQueryWrapper<Notice>()
                .eq(Notice::getUserId, userId)
                .eq(Notice::getReadFlag, false));
    }
}
