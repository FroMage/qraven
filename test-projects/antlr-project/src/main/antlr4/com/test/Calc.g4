grammar Calc;
expr : expr (MUL | DIV) expr
     | expr (ADD | SUB) expr
     | INT
     | '(' expr ')'
     ;
MUL : '*' ;
DIV : '/' ;
ADD : '+' ;
SUB : '-' ;
INT : [0-9]+ ;
WS : [ \t\r\n]+ -> skip ;
