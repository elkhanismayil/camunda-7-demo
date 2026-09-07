package az.company.camunda.auth;

import az.company.camunda.exception.ApplicationException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/register")
public class RegistrationViewController {

    private static final String FORM_VIEW = "auth/register";
    private static final String EMPTY = "";

    private final RegistrationService registrationService;

    public RegistrationViewController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping
    public String form(Model model) {
        model.addAttribute("form", emptyForm());
        return FORM_VIEW;
    }

    @PostMapping
    public String register(@Valid @ModelAttribute("form") RegistrationRequest form,
                           BindingResult bindingResult,
                           Model model,
                           RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("errors", messagesFrom(bindingResult));
            return FORM_VIEW;
        }

        RegistrationResponse registration = registrationService.register(form);

        redirectAttributes.addFlashAttribute("message",
                "Account " + registration.username() + " created with read-only access - sign in to continue.");
        // Anonymous visitors are sent to the login page from here, which is where
        // they need to go next anyway.
        return "redirect:/orders";
    }

    // The form is re-rendered rather than replaced by the JSON error body the REST
    // advice produces; a browser on a sign-up page needs the page back.
    @ExceptionHandler(ApplicationException.class)
    public String handleRegistrationFailure(ApplicationException exception, Model model) {
        model.addAttribute("errors", List.of(exception.getMessage()));
        // The submitted values do not survive into an exception handler, so the form
        // is rebuilt empty rather than left null for the template.
        model.addAttribute("form", emptyForm());
        return FORM_VIEW;
    }

    private List<String> messagesFrom(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .map(this::describe)
                .toList();
    }

    private String describe(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }

    private RegistrationRequest emptyForm() {
        return new RegistrationRequest(EMPTY, EMPTY, EMPTY, EMPTY, EMPTY);
    }
}
