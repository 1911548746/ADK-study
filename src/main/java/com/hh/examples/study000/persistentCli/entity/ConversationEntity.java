package com.hh.examples.study000.persistentCli.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@TableName("conversations")
public class ConversationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String userId;
    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
