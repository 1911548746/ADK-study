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
@TableName("state_values")
public class StateValueEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String userId;
    private String scope;
    private String stateKey;
    private String stateValueJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
