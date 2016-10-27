<!doctype html>
<html class="fixed sidebar-light">
<head>
    <meta charset="UTF-8">

    <title>$page.title$$if(!page.home.active)$ · $page.home.title$$endif$</title>
    <meta name="keywords" content="scala, streams, streaming, reactive, asynchronous, non-blocking" />
    <meta name="description" content='$page.properties.("project.description")$'>
    <meta name="author" content="The swave team">

    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
    <link href="https://fonts.googleapis.com/css?family=Open+Sans:300,400,600,700,800|Shadows+Into+Light" rel="stylesheet" type="text/css">
    <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css" />
    <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/font-awesome/4.6.3/css/font-awesome.min.css" />
    <link rel="stylesheet" href="$page.base$assets/css/theme.css" />
    <link rel="stylesheet" href="$page.base$assets/css/skins/default.css" />
    <link rel="stylesheet" href="$page.base$assets/css/theme-custom.css">
    <link rel="stylesheet" href="$page.base$assets/vendor/highlight/github.css">
    <script src="$page.base$assets/vendor/modernizr/modernizr.js"></script>
</head>
<body $if(page.properties.id)$id="$page.properties.id$"$endif$>
<section class="body">

    <header class="header">
        <div class="logo-container">
            <a href="$page.home.href$" class="logo">
                <img src="$page.base$assets/img/swave-logo.svg" height="35" alt="swave" />
            </a>
            <div class="visible-xs toggle-sidebar-left" data-toggle-class="sidebar-left-opened" data-target="html" data-fire-event="sidebar-left-opened">
                <i class="fa fa-bars" aria-label="Toggle sidebar"></i>
            </div>
        </div>

        <div class="header-right hidden-xs">
            <ul class="social-icons">
                <li class="social-icons-github"><a href="https://github.com/sirthias/swave/" target="_blank" title="Github"><i class="fa fa-github"></i></a></li>
                <li class="social-icons-email"><a href="https://groups.google.com/forum/#!forum/swave-user" target="_blank" title="Mailing List"><i class="fa fa-envelope"></i></a></li>
                <li class="social-icons-twitter"><a href="https://twitter.com/swaveio/" target="_blank" title="Twitter"><i class="fa fa-twitter"></i></a></li>
            </ul>
            <div id="talks-link">
                <a href="$page.base$introduction/talks-on-swave.html" title="Watch the latest introductory talks for swave!">
                    <i class="fa fa-chevron-right" aria-hidden="true"></i>&nbsp;
                    <i class="fa fa-video-camera" aria-hidden="true"></i>&nbsp;Watch an intro talk on swave!&nbsp;
                    <i class="fa fa-chevron-left" aria-hidden="true"></i>
                </a>
            </div>
        </div>
    </header>

    <div class="inner-wrapper">
        <aside id="sidebar-left" class="sidebar-left">

            <div class="sidebar-header">
                <div class="sidebar-title">
                    Content
                </div>
                <div class="sidebar-toggle hidden-xs" data-toggle-class="sidebar-left-collapsed" data-target="html" data-fire-event="sidebar-left-toggle">
                    <i class="fa fa-bars" aria-label="Toggle sidebar"></i>
                </div>
            </div>

            <div class="nano">
                <div class="nano-content">
                    <nav id="menu" class="nav-main" role="navigation">
                        $page.navigation$
                    </nav>
                </div>

                <script>
                    // Maintain Scroll Position
                    if (typeof localStorage !== 'undefined') {
                        if (localStorage.getItem('sidebar-left-position') !== null) {
                            var initialPosition = localStorage.getItem('sidebar-left-position'),
                                    sidebarLeft = document.querySelector('#sidebar-left .nano-content');

                            sidebarLeft.scrollTop = initialPosition;
                        }
                    }
                </script>

            </div>

        </aside>

        <section role="main" class="content-body">
            <header class="page-header">
                $page.breadcrumbs$
            </header>
            <div id="main-content">
                <!-- start: page -->
$page.content$
                $if(page.next.html)$
                <div class="nav-next">
                    <p><strong>Next:</strong> $page.next.html$</p>
                </div>
                $endif$
                <!-- end: page -->
            </div>
        </section>
    </div>
</section>

<script src="https://ajax.googleapis.com/ajax/libs/jquery/3.1.1/jquery.min.js"></script>
<script src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js"></script>
<script src="$page.base$assets/vendor/jquery.browser.mobile/jquery.browser.mobile.js"></script>
<script src="$page.base$assets/vendor/nanoscroller/nanoscroller.js"></script>
<script src="$page.base$assets/vendor/highlight/highlight.js"></script>
<script src="$page.base$assets/js/theme.js"></script>
<script src="$page.base$assets/js/theme.init.js"></script>

</body>
</html>