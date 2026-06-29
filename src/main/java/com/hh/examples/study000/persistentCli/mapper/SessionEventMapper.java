package com.hh.examples.study000.persistentCli.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.examples.study000.persistentCli.entity.SessionEventEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionEventMapper extends BaseMapper<SessionEventEntity> {
}
