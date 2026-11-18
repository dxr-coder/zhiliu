package com.smartflow.server.controller;

import com.smartflow.common.Result;
import com.smartflow.pojo.entity.MeetingRoom;
import com.smartflow.pojo.entity.RoomBooking;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.service.RoomService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    public Result<List<MeetingRoom>> rooms() {
        return Result.ok(roomService.listRooms());
    }

    @PostMapping("/bookings")
    public Result<RoomBooking> book(HttpServletRequest request, @RequestBody RoomBooking body) {
        return Result.ok(roomService.book(LoginUser.require(request), body));
    }

    /** 当日/指定日全部有效预订（选时段实时占用）。?date=yyyy-MM-dd 默认今天，不传 date 则近 7 天 */
    @GetMapping("/bookings/day")
    public Result<java.util.List<com.smartflow.pojo.entity.RoomBooking>> dayBookings(
            @RequestParam(required = false) String date) {
        return Result.ok(roomService.dayBookings(date));
    }

    @GetMapping("/bookings/my")
    public Result<List<RoomBooking>> my(HttpServletRequest request) {
        return Result.ok(roomService.myList(LoginUser.require(request)));
    }

    @PostMapping("/bookings/{id}/check-in")
    public Result<RoomBooking> checkIn(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(roomService.checkIn(LoginUser.require(request), id));
    }

    @PostMapping("/bookings/{id}/cancel")
    public Result<RoomBooking> cancel(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(roomService.cancel(LoginUser.require(request), id));
    }
}
