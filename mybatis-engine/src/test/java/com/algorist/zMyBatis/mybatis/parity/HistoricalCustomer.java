package com.algorist.zMyBatis.mybatis.parity;

import java.sql.Timestamp;

public class HistoricalCustomer {
    private Long id;
    private String name;
    private String email;
    private Long countryId;
    private String paymentGatewayCustomerId;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Long getCountryId() { return countryId; }
    public void setCountryId(Long countryId) { this.countryId = countryId; }
    public String getPaymentGatewayCustomerId() { return paymentGatewayCustomerId; }
    public void setPaymentGatewayCustomerId(String paymentGatewayCustomerId) { this.paymentGatewayCustomerId = paymentGatewayCustomerId; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
