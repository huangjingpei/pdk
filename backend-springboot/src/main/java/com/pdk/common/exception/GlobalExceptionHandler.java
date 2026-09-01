package com.pdk.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import com.pdk.common.api.CommonResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public CommonResult<Void> handleNotLogin(NotLoginException e) {
        return CommonResult.failed(40100, "登录状态无效或已过期，请重新登录");
    }

    @ExceptionHandler(BusinessException.class)
    public CommonResult<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 [{}]: {}", request.getRequestURI(), e.getMessage());
        return CommonResult.failed(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResult<Void> handleValidationException(MethodArgumentNotValidException e) {
        String defaultMessage = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数校验异常: {}", defaultMessage);
        return CommonResult.failed(40001, defaultMessage);
    }

    @ExceptionHandler(BindException.class)
    public CommonResult<Void> handleBindException(BindException e) {
        String defaultMessage = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return CommonResult.failed(40001, defaultMessage);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public CommonResult<Void> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest request) {
        log.warn("唯一约束冲突 [{}]: {}", request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return CommonResult.failed(40901, "数据已被其他请求创建或占用，请刷新后重试");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public CommonResult<Void> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        log.warn("资源不存在 [{}]", request.getRequestURI());
        return CommonResult.failed(40400, "请求的资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public CommonResult<Void> handleGeneralException(Exception e, HttpServletRequest request) {
        log.error("系统未知未捕获异常 [{}]: ", request.getRequestURI(), e);
        return CommonResult.failed(50000, "服务端处理异常，请联系管理员并提供请求时间");
    }
}
