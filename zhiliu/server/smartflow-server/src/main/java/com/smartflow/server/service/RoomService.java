package com.smartflow.server.service;

import static com.smartflow.server.service.NumberGen.genNo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.BusinessException;
import com.smartflow.pojo.entity.MeetingRoom;
import com.smartflow.pojo.entity.RoomBooking;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.MeetingRoomMapper;
import com.smartflow.server.mapper.RoomBookingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RoomService {

    private final MeetingRoomMapper roomMapper;
    private final RoomBookingMapper bookingMapper;
    private final NoticeService noticeService;
    private final AuditService auditService;

    public RoomService(MeetingRoomMapper roomMapper,
                       RoomBookingMapper bookingMapper,
                       NoticeService noticeService,
                       AuditService auditService) {
        this.roomMapper = roomMapper;
        this.bookingMapper = bookingMapper;
        this.noticeService = noticeService;
        this.auditService = auditService;
    }

    public List<MeetingRoom> listRooms() {
        return roomMapper.selectList(new LambdaQueryWrapper<MeetingRoom>()
                .eq(MeetingRoom::getStatus, "active")
                .orderByAsc(MeetingRoom::getCode));
    }

    @Transactional
    public RoomBooking book(LoginUser user, RoomBooking body) {
        if (body.getRoomId() == null || body.getStartAt() == null || body.getEndAt() == null) {
            throw new BusinessException("roomId/起止时间必填");
        }
        if (!body.getEndAt().isAfter(body.getStartAt())) {
            throw new BusinessException("结束必须晚于开始");
        }
        if (body.getStartAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("不能预订过去时段");
        }
        MeetingRoom room = roomMapper.selectById(body.getRoomId());
        if (room == null || !"active".equals(room.getStatus())) {
            throw new BusinessException(404, "会议室不存在或停用");
        }
        // 冲突：同 room 时间重叠 [start, end)
        Long overlap = bookingMapper.selectCount(new LambdaQueryWrapper<RoomBooking>()
                .eq(RoomBooking::getRoomId, body.getRoomId())
                .in(RoomBooking::getStatus, "booked", "checked_in")
                .lt(RoomBooking::getStartAt, body.getEndAt())
                .gt(RoomBooking::getEndAt, body.getStartAt()));
        if (overlap != null && overlap > 0) {
            throw new BusinessException(409, "时段已被预订");
        }

        body.setOrganizerId(user.uid());
        body.setStatus("booked");
        body.setBookingNo(genNo("M"));
        // 开场 15 分钟内签到
        body.setCheckInDeadline(body.getStartAt().plusMinutes(15));
        body.setLateCancelFlag(false);
        body.setCreditDelta(0);
        body.setCreatedAt(LocalDateTime.now());
        bookingMapper.insert(body);
        auditService.record(user.uid(), "room.book", "room_booking", body.getBookingNo(),
                null, "booked", null, null);
        return body;
    }

    /** 签到：开场前 15 分钟内。 */
    @Transactional
    public RoomBooking checkIn(LoginUser user, Long id) {
        RoomBooking b = loadOwnedOrOrganizer(user, id);
        if (!"booked".equals(b.getStatus())) {
            throw new BusinessException(409, "仅 booked 可签到");
        }
        if (LocalDateTime.now().isAfter(b.getCheckInDeadline())) {
            throw new BusinessException(409, "已过签到截止，不可签到");
        }
        b.setStatus("checked_in");
        bookingMapper.updateById(b);
        auditService.record(user.uid(), "room.check_in", "room_booking", b.getBookingNo(),
                "booked", "checked_in", null, null);
        return b;
    }

    /**
     * 取消：开场 ≥2h 免费；临期取消 late_cancel 扣信用 1。
     */
    @Transactional
    public RoomBooking cancel(LoginUser user, Long id) {
        RoomBooking b = loadOwnedOrOrganizer(user, id);
        if (!"booked".equals(b.getStatus())) {
            throw new BusinessException(409, "仅 booked 可取消");
        }
        boolean late = b.getStartAt().minusHours(2).isBefore(LocalDateTime.now());
        if (late) {
            b.setStatus("late_cancel");
            b.setLateCancelFlag(true);
            b.setCreditDelta(-1);
        } else {
            b.setStatus("cancelled");
        }
        bookingMapper.updateById(b);
        auditService.record(user.uid(), "room.cancel", "room_booking", b.getBookingNo(),
                "booked", b.getStatus(), null, null);
        return b;
    }

    /** 爽约释放（由 job/手动调用）：过 checkInDeadline 仍 booked -> no_show。 */
    @Transactional
    public int releaseNoShow() {
        LocalDateTime now = LocalDateTime.now();
        List<RoomBooking> stale = bookingMapper.selectList(new LambdaQueryWrapper<RoomBooking>()
                .eq(RoomBooking::getStatus, "booked")
                .lt(RoomBooking::getCheckInDeadline, now));
        for (RoomBooking b : stale) {
            b.setStatus("no_show");
            b.setCreditDelta(-1);
            bookingMapper.updateById(b);
            noticeService.push(b.getOrganizerId(), "ticket",
                    "会议室爽约 " + b.getBookingNo(), "已释放时段", "room", b.getId());
            auditService.record(null, "room.no_show", "room_booking", b.getBookingNo(),
                    "booked", "no_show", null, null);
        }
        return stale.size();
    }

    /** 指定日（默认今天）有效预订；用于前端选时段提示占用。 */
    public List<RoomBooking> dayBookings(String date) {
        java.time.LocalDate d = date == null || date.isBlank()
                ? java.time.LocalDate.now()
                : java.time.LocalDate.parse(date);
        java.time.LocalDateTime dayStart = d.atStartOfDay();
        java.time.LocalDateTime dayEnd = d.plusDays(1).atStartOfDay();
        return bookingMapper.selectList(new LambdaQueryWrapper<RoomBooking>()
                .in(RoomBooking::getStatus, "booked", "checked_in")
                .lt(RoomBooking::getStartAt, dayEnd)
                .gt(RoomBooking::getEndAt, dayStart)
                .orderByAsc(RoomBooking::getStartAt));
    }

    public List<RoomBooking> myList(LoginUser user) {
        return bookingMapper.selectList(new LambdaQueryWrapper<RoomBooking>()
                .eq(RoomBooking::getOrganizerId, user.uid())
                .orderByDesc(RoomBooking::getStartAt));
    }

    private RoomBooking loadOwnedOrOrganizer(LoginUser user, Long id) {
        RoomBooking b = bookingMapper.selectById(id);
        if (b == null) {
            throw new BusinessException(404, "预订不存在");
        }
        if (!b.getOrganizerId().equals(user.uid()) && !user.isAdmin()) {
            throw new BusinessException(403, "仅组织者可操作");
        }
        return b;
    }
}
