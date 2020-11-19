{
  network = {
    name = "small network";
    defaults = {
      ssh-key = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCuI2T0KT2g5Z7xDfA36eypCTVUW+AZA6Q7/0Hvvdm3wqSyYHokw1Y7pLv/9+K/VL35vpfN368f5FAlbqYiM0p4UKrM7RgtgkyC77xg2ZXnJYNCwLHMph/GdteTgg/fBWXIzFeTMQCME3pILVhCYuQL2qJm0diihf7zuI1r+jxdwhMjY6BNNgfJ6SY1LZ7p0p9zGdGJGypEuGDFAhP5sGIiypLSpl/C0GOpYJzKUWR8MzMPsbfK/klpKS+AXGwmr27s8VmmzYFzueFExiFgDQ9N8EWO/XMtse1d4CLe+4tPYHzIqrIPdZXh11TdcmUL8g/lykOufPeLNmvXtEXCXxQx cse-server-root-key";
      image = "nixos-20.09";
    };
  };

  m1 = {
    host = true;
    copies = 2;
    module = {config, pkgs, ...} : {
      environment.systemPackages = [pkgs.htop];
      users.motd = "HELLOOOO";
    };
  };
}
