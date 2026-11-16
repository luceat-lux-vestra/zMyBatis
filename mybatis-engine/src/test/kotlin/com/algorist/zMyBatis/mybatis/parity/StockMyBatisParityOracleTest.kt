package com.algorist.zMyBatis.mybatis.parity

import com.algorist.zMyBatis.mybatis.stockEffectiveParameterMappingMetadata
import java.io.InputStream
import java.lang.reflect.Method
import java.math.BigDecimal
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.reflection.ParamNameResolver
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StockMyBatisParityOracleTest {

    @Test
    fun ordinaryResultAndParameterMetadataDoNotChangeSelectedStatementBoundSql() {
        val full = configuration("parity/ordinary-full.xml")
        val minimal = configuration("parity/ordinary-minimal.xml")
        val values = linkedMapOf<String, Any?>("id" to 42L, "name" to "pro")

        val fullResultMap = snapshot(full, "parity.OrdinaryFull.withResultMap", values)
        val minimalResultMap = snapshot(minimal, "parity.OrdinaryMinimal.withResultMap", values)
        assertEquals(minimalResultMap.sql, fullResultMap.sql)
        assertEquals(minimalResultMap.bindings, fullResultMap.bindings)
        assertEquals(
            "SELECTid,name,activeFROMproductsWHEREid=?",
            compact(fullResultMap.sql),
        )
        assertEquals(listOf("id"), fullResultMap.bindings.map { it.property })
        assertEquals(listOf(42L), fullResultMap.bindings.map { it.runtimeValue })
        assertEquals("BIGINT", fullResultMap.bindings.single().jdbcTypeIdentity)
        assertTrue(full.hasCache("parity.OrdinaryFull"))

        val fullResultType = snapshot(full, "parity.OrdinaryFull.withResultType", values)
        val minimalResultType = snapshot(minimal, "parity.OrdinaryMinimal.withResultType", values)
        assertEquals(minimalResultType.sql, fullResultType.sql)
        assertEquals(minimalResultType.bindings, fullResultType.bindings)
        assertEquals(
            "SELECTid,nameFROMproductsWHEREname=?",
            compact(fullResultType.sql),
        )
        assertEquals(listOf("name"), fullResultType.bindings.map { it.property })
        assertEquals(listOf("pro"), fullResultType.bindings.map { it.runtimeValue })
        assertEquals("VARCHAR", fullResultType.bindings.single().jdbcTypeIdentity)
    }

    @Test
    fun includeGraphsPreserveSameCrossNestedParameterizedAndDatabaseIdSemantics() {
        val defaults = configuration(
            "parity/include-shared.xml",
            "parity/include-root.xml",
        )

        assertEquals(
            "SELECTid,nameFROMproducts",
            compact(snapshot(defaults, "parity.IncludeMapper.sameNamespace", null).sql),
        )

        val cross = snapshot(
            defaults,
            "parity.IncludeMapper.crossNamespace",
            mapOf("active" to true),
        )
        assertEquals(
            "SELECTid,name,activeFROMproductsWHERE1=1ANDactive=?",
            compact(cross.sql),
        )
        assertEquals(listOf("active"), cross.bindings.map { it.property })
        assertEquals(listOf(true), cross.bindings.map { it.runtimeValue })
        assertEquals("BOOLEAN", cross.bindings.single().jdbcTypeIdentity)

        val nested = snapshot(
            defaults,
            "parity.IncludeMapper.nested",
            mapOf("tenantId" to 77L),
        )
        assertEquals(
            "SELECTidFROMproductsWHERE1=1ANDtenant_id=?",
            compact(nested.sql),
        )
        assertEquals(listOf("tenantId"), nested.bindings.map { it.property })
        assertEquals(listOf(77L), nested.bindings.map { it.runtimeValue })

        assertEquals(
            "SELECTidFROMproductsWHERE1=1ANDvendor='default'",
            compact(snapshot(defaults, "parity.IncludeMapper.databaseId", null).sql),
        )

        val postgres = configuration(
            "parity/include-shared.xml",
            "parity/include-root.xml",
            databaseId = "PostgreSQL",
        )
        assertEquals(
            "SELECTidFROMproductsWHERE1=1ANDvendor='postgres'",
            compact(snapshot(postgres, "parity.IncludeMapper.databaseId", null).sql),
        )
    }

    @Test
    fun everyStandardDynamicTagFamilyHasAnIndependentStockOracleCase() {
        val configuration = configuration("parity/dynamic.xml")

        val ifActive = snapshot(
            configuration,
            "parity.DynamicMapper.ifCase",
            mapOf("enabled" to true, "id" to 7L),
        )
        assertEquals("SELECTidFROMproductsWHEREid=?", compact(ifActive.sql))
        assertEquals(listOf("id"), ifActive.bindings.map { it.property })

        val ifInactive = snapshot(
            configuration,
            "parity.DynamicMapper.ifCase",
            mapOf("enabled" to false, "id" to 7L),
        )
        assertEquals("SELECTidFROMproducts", compact(ifInactive.sql))
        assertTrue(ifInactive.bindings.isEmpty())

        val chooseName = snapshot(
            configuration,
            "parity.DynamicMapper.chooseCase",
            mapOf("mode" to "name", "name" to "basic", "id" to 1L),
        )
        assertEquals("SELECTidFROMproductsWHEREname=?", compact(chooseName.sql))
        assertEquals(listOf("name"), chooseName.bindings.map { it.property })

        val chooseId = snapshot(
            configuration,
            "parity.DynamicMapper.chooseCase",
            mapOf("mode" to "id", "name" to "basic", "id" to 1L),
        )
        assertEquals("SELECTidFROMproductsWHEREid=?", compact(chooseId.sql))
        assertEquals(listOf("id"), chooseId.bindings.map { it.property })

        val chooseOtherwise = snapshot(
            configuration,
            "parity.DynamicMapper.chooseCase",
            mapOf("mode" to "other", "name" to "basic", "id" to 1L),
        )
        assertEquals("SELECTidFROMproductsWHERE1=0", compact(chooseOtherwise.sql))
        assertTrue(chooseOtherwise.bindings.isEmpty())

        val foreach = snapshot(
            configuration,
            "parity.DynamicMapper.foreachCase",
            mapOf("ids" to listOf(7L, 9L)),
        )
        assertEquals("SELECTidFROMproductsWHEREidIN(?,?)", compact(foreach.sql))
        assertEquals(listOf(7L, 9L), foreach.bindings.map { it.runtimeValue })
        assertTrue(foreach.bindings.all { it.additionalParameter })

        val where = snapshot(
            configuration,
            "parity.DynamicMapper.whereCase",
            mapOf("name" to "pro", "active" to true),
        )
        assertEquals(
            "SELECTidFROMproductsWHEREname=?ANDactive=?",
            compact(where.sql),
        )
        assertEquals(listOf("name", "active"), where.bindings.map { it.property })

        val set = snapshot(
            configuration,
            "parity.DynamicMapper.setCase",
            mapOf("name" to "pro", "active" to true, "id" to 9L),
        )
        assertEquals(
            "UPDATEproductsSETname=?,active=?WHEREid=?",
            compact(set.sql),
        )
        assertEquals(listOf("name", "active", "id"), set.bindings.map { it.property })

        val trim = snapshot(
            configuration,
            "parity.DynamicMapper.trimCase",
            mapOf("name" to "pro", "active" to true),
        )
        assertEquals(
            "SELECTidFROMproductsWHEREname=?ORactive=?",
            compact(trim.sql),
        )
        assertEquals(listOf("name", "active"), trim.bindings.map { it.property })

        val bind = snapshot(
            configuration,
            "parity.DynamicMapper.bindCase",
            mapOf("name" to "pro"),
        )
        assertEquals(
            "SELECTidFROMproductsWHEREnameLIKE?",
            compact(bind.sql),
        )
        assertEquals(listOf("pattern"), bind.bindings.map { it.property })
        assertEquals(listOf("%pro%"), bind.bindings.map { it.runtimeValue })
        assertTrue(bind.bindings.single().additionalParameter)
    }

    @Test
    fun representativeDynamicCompositionsRemainStockOwned() {
        val configuration = configuration("parity/dynamic.xml")

        val chooseForeach = snapshot(
            configuration,
            "parity.DynamicMapper.chooseForeach",
            mapOf("ids" to listOf(2L, 4L)),
        )
        assertEquals(
            "SELECTidFROMproductsWHEREidIN(?,?)",
            compact(chooseForeach.sql),
        )
        assertEquals(listOf(2L, 4L), chooseForeach.bindings.map { it.runtimeValue })
        assertTrue(chooseForeach.bindings.all { it.additionalParameter })

        val chooseFallback = snapshot(
            configuration,
            "parity.DynamicMapper.chooseForeach",
            mapOf("ids" to emptyList<Long>()),
        )
        assertEquals("SELECTidFROMproductsWHERE1=0", compact(chooseFallback.sql))
        assertTrue(chooseFallback.bindings.isEmpty())

        val bindIf = snapshot(
            configuration,
            "parity.DynamicMapper.bindIf",
            mapOf("name" to "customer"),
        )
        assertEquals(
            "SELECTidFROMproductsWHEREnameLIKE?",
            compact(bindIf.sql),
        )
        assertEquals(listOf("%customer%"), bindIf.bindings.map { it.runtimeValue })
        assertTrue(bindIf.bindings.single().additionalParameter)

        val foreachWhere = snapshot(
            configuration,
            "parity.DynamicMapper.foreachWhere",
            mapOf("ids" to listOf(10L, 20L)),
        )
        assertEquals(
            "SELECTidFROMproductsWHEREidIN(?,?)",
            compact(foreachWhere.sql),
        )
        assertEquals(listOf(10L, 20L), foreachWhere.bindings.map { it.runtimeValue })

        val setWhere = snapshot(
            configuration,
            "parity.DynamicMapper.setWhere",
            mapOf("name" to "updated", "active" to null, "id" to 5L, "tenantId" to 8L),
        )
        assertEquals(
            "UPDATEproductsSETname=?WHEREid=?ANDtenant_id=?",
            compact(setWhere.sql),
        )
        assertEquals(listOf("name", "id", "tenantId"), setWhere.bindings.map { it.property })
        assertEquals(listOf("updated", 5L, 8L), setWhere.bindings.map { it.runtimeValue })
    }

    @Test
    fun stockParamNameResolverDefinesMapperMethodInputShapes() {
        val configuration = configuration("parity/parameter-shapes.xml")

        val scalarMethod = method(OracleParameterMapper::class.java, "scalar")
        val scalar = namedParameters(configuration, scalarMethod, 41L)
        assertEquals(41L, scalar)
        assertEquals(
            listOf(41L),
            snapshot(configuration, "parity.ParameterShapes.scalar", scalar).bindings.map { it.runtimeValue },
        )

        val multipleMethod = method(OracleParameterMapper::class.java, "multiple")
        val multiple = namedParameters(configuration, multipleMethod, 7L, "seven")
        assertTrue(multiple is Map<*, *>)
        val multipleMap = multiple as Map<*, *>
        assertEquals(7L, multipleMap["id"])
        assertEquals("seven", multipleMap["name"])
        assertEquals(7L, multipleMap["param1"])
        assertEquals("seven", multipleMap["param2"])
        assertEquals(
            listOf(7L, "seven"),
            snapshot(configuration, "parity.ParameterShapes.multiple", multiple).bindings.map { it.runtimeValue },
        )

        val product = HistoricalProduct().apply {
            id = 8L
            name = "object"
            price = BigDecimal("12.3400")
        }
        val objectMethod = method(OracleParameterMapper::class.java, "object")
        val objectParameter = namedParameters(configuration, objectMethod, product)
        assertSame(product, objectParameter)
        val objectSnapshot = snapshot(configuration, "parity.ParameterShapes.object", objectParameter)
        assertEquals(listOf("id", "name", "price"), objectSnapshot.bindings.map { it.property })
        assertEquals(listOf(8L, "object", BigDecimal("12.3400")), objectSnapshot.bindings.map { it.runtimeValue })

        val mapValue = linkedMapOf<String, Any?>(
            "id" to 9L,
            "nested" to mapOf("name" to "nested"),
        )
        val mapMethod = method(OracleParameterMapper::class.java, "map")
        val mapParameter = namedParameters(configuration, mapMethod, mapValue)
        assertSame(mapValue, mapParameter)
        assertEquals(
            listOf(9L, "nested"),
            snapshot(configuration, "parity.ParameterShapes.map", mapParameter).bindings.map { it.runtimeValue },
        )

        val listMethod = method(OracleParameterMapper::class.java, "list")
        val listParameter = namedParameters(configuration, listMethod, listOf(1L, 2L))
        assertTrue(listParameter is Map<*, *>)
        val listMap = listParameter as Map<*, *>
        assertEquals(listOf(1L, 2L), listMap["list"])
        assertEquals(listOf(1L, 2L), listMap["collection"])
        assertEquals(
            listOf(1L, 2L),
            snapshot(configuration, "parity.ParameterShapes.list", listMap).bindings.map { it.runtimeValue },
        )

        val collectionMethod = method(OracleParameterMapper::class.java, "collection")
        val collectionValue: Collection<Long> = linkedSetOf(3L, 4L)
        val collectionParameter = namedParameters(configuration, collectionMethod, collectionValue)
        assertTrue(collectionParameter is Map<*, *>)
        val collectionMap = collectionParameter as Map<*, *>
        assertEquals(collectionValue, collectionMap["collection"])
        assertEquals(
            listOf(3L, 4L),
            snapshot(configuration, "parity.ParameterShapes.collection", collectionMap).bindings.map { it.runtimeValue },
        )

        val arrayMethod = method(OracleParameterMapper::class.java, "array")
        val arrayValue = arrayOf(5L, 6L)
        val arrayParameter = namedParameters(configuration, arrayMethod, arrayValue)
        assertTrue(arrayParameter is Map<*, *>)
        val arrayMap = arrayParameter as Map<*, *>
        assertTrue(arrayMap["array"] === arrayValue)
        assertEquals(
            listOf(5L, 6L),
            snapshot(configuration, "parity.ParameterShapes.array", arrayMap).bindings.map { it.runtimeValue },
        )

        val typedNull = snapshot(
            configuration,
            "parity.ParameterShapes.typedNull",
            mapOf("value" to null),
        )
        assertEquals(listOf(null), typedNull.bindings.map { it.runtimeValue })
        assertEquals("java.lang.String", typedNull.bindings.single().javaTypeIdentity)
        assertEquals("VARCHAR", typedNull.bindings.single().jdbcTypeIdentity)
        assertEquals(
            "org.apache.ibatis.type.StringTypeHandler",
            typedNull.bindings.single().typeHandlerIdentity,
        )
    }

    @Test
    fun directJavaAnnotationsUseStockMapperParsingAndParamNameResolution() {
        val configuration = Configuration().apply {
            addMapper(OracleAnnotatedMapper::class.java)
        }
        val namespace = OracleAnnotatedMapper::class.java.name

        val select = snapshot(configuration, "$namespace.selectStatic", null)
        assertEquals("SELECT1", compact(select.sql))
        assertTrue(select.bindings.isEmpty())

        val insertMethod = method(OracleAnnotatedMapper::class.java, "insertOne")
        val insertParameter = namedParameters(configuration, insertMethod, 1L, "one")
        val insert = snapshot(configuration, "$namespace.insertOne", insertParameter)
        assertEquals("INSERTINTOproducts(id,name)VALUES(?,?)", compact(insert.sql))
        assertEquals(listOf("id", "name"), insert.bindings.map { it.property })
        assertEquals(listOf(1L, "one"), insert.bindings.map { it.runtimeValue })

        val updateMethod = method(OracleAnnotatedMapper::class.java, "updateOne")
        val update = snapshot(
            configuration,
            "$namespace.updateOne",
            namedParameters(configuration, updateMethod, 2L, "two"),
        )
        assertEquals("UPDATEproductsSETname=?WHEREid=?", compact(update.sql))
        assertEquals(listOf("name", "id"), update.bindings.map { it.property })

        val deleteMethod = method(OracleAnnotatedMapper::class.java, "deleteOne")
        val delete = snapshot(
            configuration,
            "$namespace.deleteOne",
            namedParameters(configuration, deleteMethod, 3L),
        )
        assertEquals("DELETEFROMproductsWHEREid=?", compact(delete.sql))
        assertEquals(listOf("id"), delete.bindings.map { it.property })

        val dynamicMethod = method(OracleAnnotatedMapper::class.java, "dynamic")
        val dynamic = snapshot(
            configuration,
            "$namespace.dynamic",
            namedParameters(
                configuration,
                dynamicMethod,
                true,
                listOf(7L, 9L),
                "pro",
            ),
        )
        assertEquals(
            "SELECTidFROMproductsWHEREactive=TRUEANDidIN(?,?)ANDnameLIKE?",
            compact(dynamic.sql),
        )
        assertEquals(listOf(7L, 9L, "%pro%"), dynamic.bindings.map { it.runtimeValue })
        assertTrue(dynamic.bindings.all { it.additionalParameter })

        val rawMethod = method(OracleAnnotatedMapper::class.java, "raw")
        val rawParameter = namedParameters(configuration, rawMethod, "42")
        val raw = snapshot(configuration, "$namespace.raw", rawParameter)
        assertEquals("SELECT42ASraw_value", compact(raw.sql))
        assertTrue("raw interpolation is rendered into SQL rather than a normal ParameterMapping", raw.bindings.isEmpty())
    }

    @Test
    fun historicalProductMapperShapeFromMybatisTestRemainsValidStockMyBatis() {
        val configuration = configuration("parity/historical-product.xml")

        val find = snapshot(configuration, "com.san.sprinboot.mybatis.h2.spring_boot_myBatis_h2.mapper.ProductMapper.findById", 7L)
        assertEquals("SELECT*FROMproductsWHEREid=?", compact(find.sql))
        assertEquals(listOf("id"), find.bindings.map { it.property })
        assertEquals(listOf(7L), find.bindings.map { it.runtimeValue })

        val product = HistoricalProduct().apply {
            id = 7L
            name = "Pro"
            price = BigDecimal("29.9900")
            currency = "USD"
            billingInterval = "MONTH"
            paymentGatewayProductId = "pg-pro"
            setActive(true)
        }

        val save = snapshot(configuration, "com.san.sprinboot.mybatis.h2.spring_boot_myBatis_h2.mapper.ProductMapper.save", product)
        assertEquals(
            "INSERTINTOproducts(name,price,currency,billing_interval,payment_gateway_product_id,active)VALUES(?,?,?,?,?,?)",
            compact(save.sql),
        )
        assertEquals(
            listOf("name", "price", "currency", "billingInterval", "paymentGatewayProductId", "active"),
            save.bindings.map { it.property },
        )
        assertEquals(
            listOf("Pro", BigDecimal("29.9900"), "USD", "MONTH", "pg-pro", true),
            save.bindings.map { it.runtimeValue },
        )

        val update = snapshot(configuration, "com.san.sprinboot.mybatis.h2.spring_boot_myBatis_h2.mapper.ProductMapper.update", product)
        assertEquals(
            listOf("name", "price", "currency", "billingInterval", "paymentGatewayProductId", "active", "id"),
            update.bindings.map { it.property },
        )

        val delete = snapshot(configuration, "com.san.sprinboot.mybatis.h2.spring_boot_myBatis_h2.mapper.ProductMapper.delete", 7L)
        assertEquals(listOf(7L), delete.bindings.map { it.runtimeValue })

        val all = snapshot(configuration, "com.san.sprinboot.mybatis.h2.spring_boot_myBatis_h2.mapper.ProductMapper.findAll", null)
        assertEquals("SELECT*FROMproducts", compact(all.sql))
        assertTrue(all.bindings.isEmpty())
    }

    @Test
    fun historicalCustomerDynamicShapesFromMybatisTestRemainValidXmlOnlyEnvironments() {
        val configuration = configuration("parity/historical-customer.xml")

        val simple = snapshot(
            configuration,
            "com.san.sprinboot.mybatis.h2.spring_boot_myBatis_h2.mapper.CustomerMapper.findById",
            mapOf("t1" to 1, "t2" to 2, "test" to 11L),
        )
        assertEquals(
            "SELECTid,nameFROMcustomerscWHEREid=?",
            compact(simple.sql),
        )
        assertEquals(listOf("test"), simple.bindings.map { it.property })
        assertEquals(listOf(11L), simple.bindings.map { it.runtimeValue })

        val nested = snapshot(
            configuration,
            "com.san.sprinboot.mybatis.h2.spring_boot_myBatis_h2.mapper.CustomerMapper.findByIdJSON",
            mapOf(
                "user" to mapOf("id" to 1),
                "cust" to mapOf("name" to "test"),
                "test" to 12L,
            ),
        )
        assertEquals(
            "SELECTid,nameFROMcustomerscWHEREid=?",
            compact(nested.sql),
        )
        assertEquals(listOf("test"), nested.bindings.map { it.property })
        assertEquals(listOf(12L), nested.bindings.map { it.runtimeValue })

        val inactive = snapshot(
            configuration,
            "com.san.sprinboot.mybatis.h2.spring_boot_myBatis_h2.mapper.CustomerMapper.findByIdJSON",
            mapOf(
                "user" to mapOf("id" to 0),
                "cust" to mapOf("name" to "other"),
                "test" to 13L,
            ),
        )
        assertEquals("SELECTFROMcustomerscWHEREid=?", compact(inactive.sql))
        assertEquals(listOf(13L), inactive.bindings.map { it.runtimeValue })
    }

    private fun configuration(
        vararg resources: String,
        databaseId: String? = null,
    ): Configuration {
        val configuration = Configuration().apply {
            this.databaseId = databaseId
            typeAliasRegistry.registerAlias("Product", HistoricalProduct::class.java)
            typeAliasRegistry.registerAlias("Customer", HistoricalCustomer::class.java)
        }
        resources.forEach { resource ->
            resource(resource).use { input ->
                XMLMapperBuilder(
                    input,
                    configuration,
                    resource,
                    configuration.sqlFragments,
                ).parse()
            }
        }
        return configuration
    }

    private fun resource(name: String): InputStream =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing stock MyBatis oracle fixture: $name"
        }

    private fun snapshot(
        configuration: Configuration,
        statementId: String,
        parameterObject: Any?,
    ): OracleSnapshot {
        val boundSql = configuration.getMappedStatement(statementId).getBoundSql(parameterObject)
        return OracleSnapshot(
            sql = boundSql.sql,
            bindings = boundSql.parameterMappings.map { mapping ->
                val metadata = stockEffectiveParameterMappingMetadata(
                    configuration,
                    boundSql,
                    parameterObject,
                    mapping,
                )
                OracleBinding(
                    property = mapping.property,
                    runtimeValue = metadata.runtimeValue,
                    additionalParameter = boundSql.hasAdditionalParameter(mapping.property),
                    javaTypeIdentity = metadata.javaTypeIdentity,
                    jdbcTypeIdentity = metadata.jdbcTypeIdentity,
                    typeHandlerIdentity = metadata.typeHandlerIdentity,
                )
            },
        )
    }

    private fun method(type: Class<*>, name: String): Method =
        type.methods.single { it.name == name }

    private fun namedParameters(
        configuration: Configuration,
        method: Method,
        vararg values: Any?,
    ): Any? = ParamNameResolver(configuration, method, method.declaringClass).getNamedParams(values)

    private fun compact(sql: String): String =
        sql.replace(Regex("\\s+"), "")

    private data class OracleSnapshot(
        val sql: String,
        val bindings: List<OracleBinding>,
    )

    private data class OracleBinding(
        val property: String,
        val runtimeValue: Any?,
        val additionalParameter: Boolean,
        val javaTypeIdentity: String?,
        val jdbcTypeIdentity: String?,
        val typeHandlerIdentity: String?,
    )
}
