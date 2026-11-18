package com.smartflow.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.pojo.entity.*;
import com.smartflow.server.mapper.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.function.ToLongFunction;

@Component
public class NumberGenInit implements ApplicationRunner {

    private final LeaveMapper leaveMapper;
    private final ExpenseMapper expenseMapper;
    private final TicketMapper ticketMapper;
    private final RoomBookingMapper bookingMapper;
    private final PurchaseRequestMapper purchaseMapper;

    public NumberGenInit(LeaveMapper leaveMapper,
                         ExpenseMapper expenseMapper,
                         TicketMapper ticketMapper,
                         RoomBookingMapper bookingMapper,
                         PurchaseRequestMapper purchaseMapper) {
        this.leaveMapper = leaveMapper;
        this.expenseMapper = expenseMapper;
        this.ticketMapper = ticketMapper;
        this.bookingMapper = bookingMapper;
        this.purchaseMapper = purchaseMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        String today = LocalDate.now().toString();
        int max = 0;
        max = Math.max(max, maxNo(leaveMapper.selectList(
                new LambdaQueryWrapper<LeaveRequest>().likeRight(LeaveRequest::getLeaveNo, "L-" + today)),
                LeaveRequest::getLeaveNo));
        max = Math.max(max, maxNo(expenseMapper.selectList(
                new LambdaQueryWrapper<Expense>().likeRight(Expense::getExpenseNo, "E-" + today)),
                Expense::getExpenseNo));
        max = Math.max(max, maxNo(ticketMapper.selectList(
                new LambdaQueryWrapper<Ticket>().likeRight(Ticket::getTicketNo, "T-" + today)),
                Ticket::getTicketNo));
        max = Math.max(max, maxNo(bookingMapper.selectList(
                new LambdaQueryWrapper<RoomBooking>().likeRight(RoomBooking::getBookingNo, "M-" + today)),
                RoomBooking::getBookingNo));
        max = Math.max(max, maxNo(purchaseMapper.selectList(
                new LambdaQueryWrapper<PurchaseRequest>().likeRight(PurchaseRequest::getDemandNo, "P-" + today)),
                PurchaseRequest::getDemandNo));
        NumberGen.seed(max);
        System.out.println("[NumberGen] seeded today max seq = " + max);
    }

    private <T> int maxNo(List<T> rows, java.util.function.Function<T, String> noFn) {
        int max = 0;
        for (T r : rows) {
            String no = noFn.apply(r);
            if (no == null) {
                continue;
            }
            int i = no.lastIndexOf('-');
            if (i >= 0) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(i + 1)));
                } catch (NumberFormatException ignore) {
                    // skip
                }
            }
        }
        return max;
    }
}
