package fixture;

import org.apache.ibatis.annotations.Param;

public interface ParityParameterTypeMapper {
    Product findByIdWithParameterType(@Param("id") Long id);
}
