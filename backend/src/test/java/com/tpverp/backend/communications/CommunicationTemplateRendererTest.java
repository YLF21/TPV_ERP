package com.tpverp.backend.communications;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.tpverp.backend.communications.CommunicationModels.*;

class CommunicationTemplateRendererTest {
    private final CommunicationTemplateRenderer renderer=new CommunicationTemplateRenderer();

    @Test void escapesUserContentAndDoesNotRecursivelyExpandInsertedVariables() {
        var input=input("Hola {name}","<script>alert(1)</script>\n{storeName}","https://shop.es/catalogue?a=1&b=2");
        var rendered=renderer.render(input,Map.of("name","<img src=x> {storeName}","storeName","Mi tienda"),"https://mail.shop.es/unsubscribe?token=x&v=1");
        assertThat(rendered.html()).contains("&lt;img src=x&gt; {storeName}","&lt;script&gt;alert(1)&lt;/script&gt;<br>Mi tienda","a=1&amp;b=2","token=x&amp;v=1");
        assertThat(rendered.html()).doesNotContain("<script>","<img src=x>");
        assertThat(rendered.text()).contains("Dar de baja / Unsubscribe / 退订");
    }
    @Test void rejectsExecutableUrlsAndHeaderInjection() {
        assertThatThrownBy(()->renderer.render(input("Oferta","Mensaje","javascript:alert(1)"),Map.of(),null)).isInstanceOf(IllegalArgumentException.class);
        var bad=new TemplateInput("Prueba","Oferta\r\nBcc: victim@example.org","Título","Texto",null,null,null,"#123456",0);
        assertThatThrownBy(()->renderer.validate(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnexpectedVariablesAndUnsafeColor() {
        assertThatThrownBy(()->renderer.validate(input("Hola {password}","Texto",null))).isInstanceOf(IllegalArgumentException.class);
        var input=new TemplateInput("Prueba","Oferta","Título","Texto",null,null,null,"red;position:fixed",0);
        assertThatThrownBy(()->renderer.validate(input)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void includesAnExplicitLogoWithoutFetchingItAndEscapesItsUrl() {
        var input=new TemplateInput("Prueba","Oferta","Título","Texto",null,null,"https://shop.es/logo?a=1&b=2","#123456",0);
        assertThat(renderer.render(input,Map.of(),null).html()).contains("referrerpolicy=\"no-referrer\"","https://shop.es/logo?a=1&amp;b=2");
    }
    @Test void stripsNewlinesFromPersonalizedSubjects() {
        assertThat(renderer.subject(input("Hola","Texto",null),Map.of("name","a\r\nb"))).isEqualTo("Oferta a  b");
    }
    private TemplateInput input(String heading,String body,String url) {
        return new TemplateInput("Prueba","Oferta {name}",heading,body,"Ver",url,null,"#123456",0);
    }
}
