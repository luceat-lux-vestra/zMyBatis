package fixture;

import org.apache.ibatis.annotations.Param;

public interface PendingCommonMapperDeclarations {
    Product findAllWithResultMap();

    Product findByIdWithParameterType(@Param("id") Long id);
}
