package fr.louis.poker.server.user;

public record UserResponse(Long id, String username) {

    public static UserResponse from(UserAccount user) {
        return new UserResponse(user.getId(), user.getUsername());
    }
}
