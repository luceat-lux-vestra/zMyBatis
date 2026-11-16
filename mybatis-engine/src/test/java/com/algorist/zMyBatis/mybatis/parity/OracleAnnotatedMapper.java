package com.algorist.zMyBatis.mybatis.parity;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OracleAnnotatedMapper {
    @Select("SELECT 1")
    Integer selectStatic();

    @Insert("INSERT INTO products(id, name) VALUES(#{id,jdbcType=BIGINT}, #{name,jdbcType=VARCHAR})")
    int insertOne(@Param("id") Long id, @Param("name") String name);

    @Update("UPDATE products SET name = #{name,jdbcType=VARCHAR} WHERE id = #{id,jdbcType=BIGINT}")
    int updateOne(@Param("id") Long id, @Param("name") String name);

    @Delete("DELETE FROM products WHERE id = #{id,jdbcType=BIGINT}")
    int deleteOne(@Param("id") Long id);

    @Select({
        "<script>",
        "<bind name='pattern' value=\"'%' + name + '%'\"/>",
        "SELECT id FROM products",
        "<where>",
        "  <if test='enabled'>active = TRUE</if>",
        "  <choose>",
        "    <when test='ids != null and ids.size > 0'>",
        "      AND id IN",
        "      <foreach collection='ids' item='id' index='idx' open='(' separator=',' close=')'>",
        "        #{id,jdbcType=BIGINT}",
        "      </foreach>",
        "    </when>",
        "    <otherwise>AND 1 = 0</otherwise>",
        "  </choose>",
        "  <if test='name != null'>AND name LIKE #{pattern,jdbcType=VARCHAR}</if>",
        "</where>",
        "</script>"
    })
    List<Map<String, Object>> dynamic(
        @Param("enabled") boolean enabled,
        @Param("ids") List<Long> ids,
        @Param("name") String name
    );

    @Select("SELECT ${value} AS raw_value")
    String raw(@Param("value") String value);
}
