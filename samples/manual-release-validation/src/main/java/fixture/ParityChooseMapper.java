package fixture;

import org.apache.ibatis.annotations.Param;

public interface ParityChooseMapper {
    Long chooseParity(@Param("enabled") Boolean enabled);
}
