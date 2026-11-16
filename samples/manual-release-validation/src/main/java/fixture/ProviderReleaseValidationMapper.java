package fixture;

import org.apache.ibatis.annotations.SelectProvider;

public interface ProviderReleaseValidationMapper {

    @SelectProvider(type = ReleaseValidationSqlProvider.class, method = "selectOne")
    Long providerSelect();
}
