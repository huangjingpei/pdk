package com.pdk.update.domain;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("pdk_client_update_operation")
public class ClientUpdateOperation {
    @TableId(type=IdType.AUTO) private Long id;
    private Long bizId; private String requestId; private String operationType; private String targetType; private String targetId;
    @TableField(fill=FieldFill.INSERT) private LocalDateTime createdAt;
}
