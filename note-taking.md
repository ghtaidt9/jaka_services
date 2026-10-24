CIDR: Classless inter - Domain routing: define a range of ip addresses
cidrhost(var.vpc.cidr, 0): returns the first address  in the range
    Call	Result
    cidrhost("10.0.0.0/16", 0)	"10.0.0.0" (1st address)
    cidrhost("10.0.0.0/16", 5)	"10.0.0.5" (6th address)
    cidrhost("10.0.0.0", 0)	error: no /prefix
    cidrhost("hello", 0)	error: not an IP
can(...) turns error into false, success into true
