package com.tpverp.backend;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HomeController {

	@GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
	String home() {
		return """
				<!doctype html>
				<html lang="es">
				<head>
					<meta charset="utf-8">
					<meta name="viewport" content="width=device-width, initial-scale=1">
					<title>TPV ERP Backend</title>
					<style>
						body { margin: 0; font-family: Segoe UI, Arial, sans-serif; background: #f6f7f9; color: #1d2430; }
						main { max-width: 860px; margin: 0 auto; padding: 40px 24px; }
						h1 { margin: 0 0 10px; font-size: 30px; }
						p { margin: 0 0 22px; color: #5b6575; }
						a { display: inline-block; margin: 0 10px 10px 0; padding: 10px 14px; border-radius: 6px; background: #185abc; color: white; text-decoration: none; font-weight: 700; }
						code { background: #e9edf5; padding: 2px 6px; border-radius: 4px; }
					</style>
				</head>
				<body>
					<main>
						<h1>TPV ERP Backend</h1>
						<p>Servicio local arrancado. Este puerto expone la API del TPV.</p>
						<a href="/swagger-ui/index.html">Abrir Swagger</a>
						<a href="/api/v1/installation/status">Estado instalacion</a>
						<p>Panel SaaS/admin: <code>http://localhost:8090/admin</code></p>
					</main>
				</body>
				</html>
				""";
	}
}
