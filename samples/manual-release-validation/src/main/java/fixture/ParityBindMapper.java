package fixture;

import org.apache.ibatis.annotations.Param;

public interface ParityBindMapper {
    String bindParity(@Param("value") String value);
}
