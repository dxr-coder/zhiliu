package com.smartflow.server.config;

import com.smartflow.common.BusinessException;
import com.smartflow.common.Result;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class DbExceptionAdvice {

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> dup(DuplicateKeyException e) {
        return Result.fail(409, "数据已存在（唯一键冲突）");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<Void> integrity(DataIntegrityViolationException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("Duplicate")) {
            return Result.fail(409, "数据已存在（唯一键冲突）");
        }
        return Result.fail(400, "数据不合法或违反约束");
    }
}
