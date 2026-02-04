package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired(required = false)
    private ClientRegistrationRepository clientRegistrationRepository;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/login.html",
                    "/login.css",
                    "/theme.css",
                    "/auth/provider",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/oauth2/**",
                    "/login/**",
                    "/favicon.ico"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/auth/login")
                .defaultSuccessUrl("/", true)
            )
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login.html"));

        if (clientRegistrationRepository != null) {
            http.oauth2Login(oauth -> oauth
                .loginPage("/login.html")
                .defaultSuccessUrl("/", true)
            );
        }

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(@Value("${app.auth.secret}") String secret,
                                          PasswordEncoder passwordEncoder) {
        return new InMemoryUserDetailsManager(
            User.builder()
                .username("user")
                .password(passwordEncoder.encode(secret))
                .roles("USER")
                .build()
        );
    }
}
