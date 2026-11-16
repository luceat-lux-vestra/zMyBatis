package fixture;

import org.apache.ibatis.annotations.Param;

public interface PendingDynamicContractMapper {
    Long choosePending(@Param("enabled") Boolean enabled);

    String bindPending(@Param("value") String value);
}
