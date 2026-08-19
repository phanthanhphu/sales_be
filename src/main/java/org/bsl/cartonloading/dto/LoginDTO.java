package org.bsl.cartonloading.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;

public class LoginDTO {

    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "Password cannot be blank")
    private String password;

    @NotBlank(message = "Buyer cannot be blank")
    private String buyerCode;

    public LoginDTO() {
    }

    public LoginDTO(String email, String password) {
        this(email, password, null);
    }

    public LoginDTO(String email, String password, String buyerCode) {
        this.email = email;
        this.password = password;
        this.buyerCode = buyerCode;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getBuyerCode() {
        return buyerCode;
    }

    public void setBuyerCode(String buyerCode) {
        this.buyerCode = buyerCode;
    }
}