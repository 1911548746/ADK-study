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
@TableName("memories")
public class MemoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String userId;
    private String sourceSessionId;
    private String sourceEventId;
    private String author;
    private String contentText;
    private String contentJson;
    private String contentHash;
    private LocalDateTime createdAt;

}
