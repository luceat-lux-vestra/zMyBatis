package com.algorist.zMyBatis.mybatis.parity;

import java.math.BigDecimal;
import java.sql.Timestamp;

public class HistoricalProduct {
    private Long id;
    private String name;
    private BigDecimal price;
    private String currency;
    private String billingInterval;
    private String paymentGatewayProductId;
    private boolean active;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getBillingInterval() { return billingInterval; }
    public void setBillingInterval(String billingInterval) { this.billingInterval = billingInterval; }
    public String getPaymentGatewayProductId() { return paymentGatewayProductId; }
    public void setPaymentGatewayProductId(String paymentGatewayProductId) { this.paymentGatewayProductId = paymentGatewayProductId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
