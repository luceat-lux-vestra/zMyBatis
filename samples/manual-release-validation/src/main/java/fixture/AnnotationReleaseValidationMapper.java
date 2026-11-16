package fixture;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AnnotationReleaseValidationMapper {

    @Select("SELECT 7 AS result_value")
    Long zeroBinding();

    @Select("SELECT #{value,jdbcType=BIGINT}::BIGINT AS result_value")
    Long bigintEcho(@Param("value") Long value);

    @Select("SELECT ${value} AS result_value")
    Long rawEcho(@Param("value") String value);

    @Insert("INSERT INTO release_probe (id, note) SELECT 9001, 'noop' WHERE FALSE")
    int insertNoop();

    @Update("UPDATE products SET active = active WHERE FALSE")
    int updateNoop();

    @Delete("DELETE FROM products WHERE FALSE")
    int deleteNoop();
}
