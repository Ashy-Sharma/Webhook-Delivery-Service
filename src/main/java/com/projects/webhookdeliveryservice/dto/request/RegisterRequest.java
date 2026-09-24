package com.projects.webhookdeliveryservice.dto.request;

import com.projects.webhookdeliveryservice.entity.Role;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {

    @NotBlank
    @Email
    @Length(max = 255)
    private String email;

    @NotBlank
    @Size(min = 8, max = 100)
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "Password must contain at least one uppercase letter, one lowercase letter and one digit"
    )
    private String password;

    @NotBlank
    @Size(min = 3, max = 50)
    @Pattern(
            regexp = "^[A-Za-z0-9_]+$",
            message = "Username may contain only letters, numbers and underscores"
    )

    private String username;

}
