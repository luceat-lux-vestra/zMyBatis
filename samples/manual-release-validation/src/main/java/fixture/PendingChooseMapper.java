package fixture;

import org.apache.ibatis.annotations.Param;

public interface PendingChooseMapper {
    Long choosePending(@Param("enabled") Boolean enabled);
}
