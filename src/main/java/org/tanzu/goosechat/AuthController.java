package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired(required = false)
    private ClientRegistrationRepository clientRegistrationRepository;

    @GetMapping("/auth/status")
    public ResponseEntity<Map<String, Object>> authStatus(Authentication authentication, Principal principal) {
        boolean isAuthenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);

        String username = null;
        String email = null;
        String displayName = null;

        if (isAuthenticated) {
            if (authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
                if (principal != null) {
                    username = principal.getName();
                }
                Object emailAttr = oAuth2User.getAttributes().get("email");
                Object nameAttr = oAuth2User.getAttributes().get("name");
                Object loginAttr = oAuth2User.getAttributes().get("login");
                email = emailAttr == null ? null : String.valueOf(emailAttr);
                if (nameAttr != null) {
                    displayName = String.valueOf(nameAttr);
                } else if (loginAttr != null) {
                    displayName = String.valueOf(loginAttr);
                } else if (username != null) {
                    displayName = username;
                }
                if (displayName == null) {
                    displayName = email;
                }
            } else if (authentication.getPrincipal() instanceof UserDetails userDetails) {
                username = userDetails.getUsername();
                displayName = username;
            } else if (principal != null) {
                username = principal.getName();
                displayName = username;
            }
        }

        return ResponseEntity.ok(Map.of(
                "authenticated", isAuthenticated,
                "username", username == null ? "" : username,
                "email", email == null ? "" : email,
                "displayName", displayName == null ? "" : displayName
        ));
    }

    @GetMapping("/auth/provider")
    public Map<String, String> authProvider() {
        String provider = "none";
        String registrationId = "";
        String[] cfCandidates = new String[]{"sso", "p-identity", "uaa", "p-identity-sso", "cf", "okta"};
        for (String id : cfCandidates) {
            if (findRegistration(id) != null) {
                provider = "cf-sso";
                registrationId = id;
                break;
            }
        }
        if ("none".equals(provider) && findRegistration("github") != null) {
            provider = "github";
            registrationId = "github";
        }
        return Map.of("provider", provider, "registrationId", registrationId);
    }

    private ClientRegistration findRegistration(String id) {
        if (clientRegistrationRepository == null) {
            return null;
        }
        try {
            return ((InMemoryClientRegistrationRepository) clientRegistrationRepository)
                    .findByRegistrationId(id);
        } catch (ClassCastException e) {
            return null;
        }
    }
}
