package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sf_purchase_request")
public class PurchaseRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String demandNo;
    private String title;
    private String category;
    private Integer qty;
    private BigDecimal budget;
    private String reason;
    private Long requesterId;
    private Long deptId;
    /** demand/quoted/approved/ordered/received/accepted/rejected/cancelled */
    private String status;
    private String quotesJson;
    private Integer chosenQuoteIndex;
    private Long approvalInstanceId;
    private Long linkedExpenseId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
