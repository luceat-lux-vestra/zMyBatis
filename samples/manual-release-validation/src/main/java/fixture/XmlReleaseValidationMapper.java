package fixture;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface XmlReleaseValidationMapper {
    Long zeroBinding();

    Long bigintEcho(@Param("value") Long value);

    Long bigintWithIf(@Param("value") Long value);

    List<Long> whereIf(@Param("enabled") Boolean enabled, @Param("id") Long id);

    List<Long> foreachIds(@Param("ids") List<Long> ids);

    Long rawEcho(@Param("value") String value);

    Long databaseIdEcho();

    String unsupportedVarchar(@Param("value") String value);

    Long unsupportedTwoBigints(@Param("left") Long left, @Param("right") Long right);

    int insertNoop();

    int updateNoop();

    int deleteNoop();

    int setTrimNoop(@Param("name") String name, @Param("id") Long id);

    Long nestedMapEcho(@Param("filter") Map<String, Object> filter);
}
