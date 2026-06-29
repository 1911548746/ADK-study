package com.hh.examples.study000.persistentCli.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.examples.study000.persistentCli.entity.MessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
