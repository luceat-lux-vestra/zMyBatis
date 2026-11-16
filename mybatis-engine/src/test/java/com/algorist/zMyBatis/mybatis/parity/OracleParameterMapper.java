package com.algorist.zMyBatis.mybatis.parity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface OracleParameterMapper {
    Object scalar(Long value);
    Object multiple(@Param("id") Long id, @Param("name") String name);
    Object object(HistoricalProduct product);
    Object map(Map<String, Object> value);
    Object list(List<Long> ids);
    Object collection(Collection<Long> ids);
    Object array(Long[] ids);
}
