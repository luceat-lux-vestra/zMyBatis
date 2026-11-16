package fixture;

import org.apache.ibatis.annotations.Param;

public interface PendingCommonMapperDeclarations {
    Product findAllWithResultMap();

    Product findAllWithResultType();

    Product findByIdWithParameterType(@Param("id") Long id);
}
