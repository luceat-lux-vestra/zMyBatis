package fixture;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ProductMapper {
    List<Product> findAllProducts();

    Product findProductById(@Param("id") Long id);
}
