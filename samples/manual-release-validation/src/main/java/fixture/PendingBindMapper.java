package fixture;

import org.apache.ibatis.annotations.Param;

public interface PendingBindMapper {
    String bindPending(@Param("value") String value);
}
