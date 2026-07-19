package com.voltx.evgenee.util;

import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtil {

	@Value("${jwt.secret}")
	private String secret;

	@Value("${jwt.expiration-ms:604800000}")
	private long expirationMs;

	private SecretKey getSigningKey()
	{
		SecretKey sKey=Keys.hmacShaKeyFor(secret.getBytes());
		return sKey;
	}
	 
	 public String generateToken(String email,List<String> roles)
	 {
		 return Jwts.builder()
				 .setSubject(email)
				 .setIssuedAt(new Date(System.currentTimeMillis()))
				 .claim("roles", roles)
				 .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
				 .signWith(getSigningKey())
				 .compact();
	 }
	
	 public boolean isTokenExpired(String token)
	 {
		 return extractClaims(token).getExpiration().before(new Date());
	 }
	 public String extractEmail(String token)
	 {
		 Claims claims=extractClaims(token);
		  return  claims.getSubject();
	 }
	 public List<String> extractRole(String token)
	 {
		 Claims claims=extractClaims(token);
		  return  (List<String>)claims.get("roles");
	 }
	
	 public Claims extractClaims(String token)
	 {
		 JwtParser parser=Jwts.parserBuilder().setSigningKey(getSigningKey()).build();
		 Jws<Claims> claimsJws=parser.parseClaimsJws(token);
		 return claimsJws.getBody();
	 }
	 public boolean validateToken(String token,String email)
	 {
		 if(isTokenExpired(token) || !extractEmail(token).equals(email))
         return false;
         return true;
	 }
     
}
