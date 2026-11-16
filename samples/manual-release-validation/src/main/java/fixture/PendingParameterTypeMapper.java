package fixture;

import org.apache.ibatis.annotations.Param;

public interface PendingParameterTypeMapper {
    Product findByIdWithParameterType(@Param("id") Long id);
}
